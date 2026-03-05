package com.logcollect.core.degrade;

import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 降级兜底处理器。
 *
 * <p>按配置优先级在文件、内存、丢弃策略之间切换，并在必要时抛出阻断异常。
 */
public final class DegradeFallbackHandler {

    private static final int MAX_MEMORY_QUEUE_SIZE = 1000;
    private static final ConcurrentLinkedQueue<String> MEMORY_QUEUE = new ConcurrentLinkedQueue<String>();
    private static final AtomicInteger MEMORY_QUEUE_SIZE = new AtomicInteger(0);

    private DegradeFallbackHandler() {
    }

    /**
     * 执行降级兜底写入。
     *
     * @param context 当前调用上下文
     * @param reason  触发降级原因
     * @param lines   待降级写入的日志行
     * @param level   当前日志级别
     * @return true 表示至少一种降级策略执行成功
     */
    public static boolean handleDegraded(LogCollectContext context, String reason, List<String> lines, String level) {
        if (context == null || context.getConfig() == null) {
            return false;
        }
        LogCollectConfig config = context.getConfig();
        if (!config.isEnableDegrade()) {
            return false;
        }

        boolean fallbackSuccess = false;
        Throwable fallbackError = null;
        DegradeStorage storage = config.getDegradeStorage();
        try {
            switch (storage) {
                case FILE:
                    fallbackSuccess = degradeToFile(context, lines) || degradeToMemory(context, lines);
                    break;
                case LIMITED_MEMORY:
                    fallbackSuccess = degradeToMemory(context, lines) || degradeToFile(context, lines);
                    break;
                case DISCARD_NON_ERROR:
                    if ("ERROR".equalsIgnoreCase(level) || "FATAL".equalsIgnoreCase(level)) {
                        fallbackSuccess = degradeToMemory(context, lines) || degradeToFile(context, lines);
                    } else {
                        fallbackSuccess = true;
                    }
                    break;
                case DISCARD_ALL:
                    fallbackSuccess = true;
                    break;
                default:
                    fallbackSuccess = false;
            }
        } catch (Exception t) {
            fallbackError = t;
            LogCollectInternalLogger.warn("Degrade fallback failed", t);
        } catch (Error e) {
            throw e;
        }

        if (!fallbackSuccess) {
            if (config.isBlockWhenDegradeFail()) {
                LogCollectInternalLogger.error(
                        "CRITICAL: All degrade strategies exhausted for method={}. blockWhenDegradeFail=true",
                        context.getMethodSignature());
                System.err.println("[LOGCOLLECT-EMERGENCY] traceId=" + context.getTraceId()
                        + " method=" + context.getMethodSignature()
                        + " reason=" + reason);
                metricCall(context, "incrementDiscarded",
                        context.getMethodSignature(), "ultimate_discard_blocked");
                String message = "LogCollect: degrade storage failed, blockWhenDegradeFail=true, method="
                        + context.getMethodSignature();
                if (fallbackError == null) {
                    throw new LogCollectDegradeException(message);
                }
                throw new LogCollectDegradeException(message, fallbackError);
            } else {
                LogCollectInternalLogger.warn("All degrade strategies exhausted, discarding. method={}",
                        context.getMethodSignature());
                metricCall(context, "incrementDiscarded",
                        context.getMethodSignature(), "ultimate_discard");
            }
        }
        return fallbackSuccess;
    }

    public static boolean handleDegraded(LogCollectContext context,
                                         List<LogEntry> entries,
                                         DegradeReason reason) {
        List<String> lines = new ArrayList<String>();
        String maxLevel = "TRACE";
        if (entries != null) {
            for (LogEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                lines.add(entry.getContent());
                maxLevel = higherLevel(maxLevel, entry.getLevel());
            }
        }
        return handleDegraded(context, code(reason), lines, maxLevel);
    }

    public static boolean handleDegraded(LogCollectContext context,
                                         String formattedLine,
                                         String level,
                                         DegradeReason reason) {
        List<String> lines = formattedLine == null
                ? new ArrayList<String>()
                : new ArrayList<String>(Collections.singletonList(formattedLine));
        return handleDegraded(context, code(reason), lines, level);
    }

    private static boolean degradeToFile(LogCollectContext context, List<String> lines) {
        Object fileManagerObj = context.getAttribute("__degradeFileManager");
        if (!(fileManagerObj instanceof DegradeFileManager)) {
            return false;
        }
        DegradeFileManager fileManager = (DegradeFileManager) fileManagerObj;
        if (!fileManager.isInitialized()) {
            return false;
        }
        List<String> copy = lines == null ? new ArrayList<String>() : new ArrayList<String>(lines);
        // blockWhenDegradeFail=true 场景要求当场感知失败，保持同步写入语义。
        if (context.getConfig() != null && context.getConfig().isBlockWhenDegradeFail()) {
            fileManager.write(context.getTraceId(), copy);
            return true;
        }
        return fileManager.writeAsync(context.getTraceId(), copy);
    }

    private static boolean degradeToMemory(LogCollectContext context, List<String> lines) {
        if (MEMORY_QUEUE_SIZE.get() >= MAX_MEMORY_QUEUE_SIZE) {
            return false;
        }
        String value;
        if (lines == null || lines.isEmpty()) {
            value = context.getTraceId() + "|" + context.getMethodSignature();
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            value = sb.toString();
        }
        MEMORY_QUEUE.offer(value);
        MEMORY_QUEUE_SIZE.incrementAndGet();
        return true;
    }

    private static void metricCall(LogCollectContext context, String methodName, Object... args) {
        if (context == null || context.getConfig() == null || !context.getConfig().isEnableMetrics()) {
            return;
        }
        LogCollectMetrics metrics = context.getAttribute("__metrics", LogCollectMetrics.class);
        if (metrics == null) {
            metrics = NoopLogCollectMetrics.INSTANCE;
        }
        if ("incrementDiscarded".equals(methodName) && args.length == 2) {
            String method = args[0] == null ? context.getMethodSignature() : String.valueOf(args[0]);
            String reason = args[1] == null ? "unknown" : String.valueOf(args[1]);
            metrics.incrementDiscarded(method, reason);
        }
    }

    private static String code(DegradeReason reason) {
        return reason == null ? "unknown" : reason.code();
    }

    private static String higherLevel(String left, String right) {
        return levelRank(right) > levelRank(left) ? right : left;
    }

    private static int levelRank(String level) {
        if (level == null) {
            return 0;
        }
        String v = level.toUpperCase();
        if ("FATAL".equals(v)) return 5;
        if ("ERROR".equals(v)) return 4;
        if ("WARN".equals(v)) return 3;
        if ("INFO".equals(v)) return 2;
        if ("DEBUG".equals(v)) return 1;
        return 0;
    }
}
