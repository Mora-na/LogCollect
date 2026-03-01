package com.logcollect.core.degrade;

import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class DegradeFallbackHandler {

    private static final int MAX_MEMORY_QUEUE_SIZE = 1000;
    private static final ConcurrentLinkedQueue<String> MEMORY_QUEUE = new ConcurrentLinkedQueue<String>();
    private static final AtomicInteger MEMORY_QUEUE_SIZE = new AtomicInteger(0);

    private DegradeFallbackHandler() {
    }

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
        } catch (Throwable t) {
            fallbackError = t;
            LogCollectInternalLogger.warn("Degrade fallback failed", t);
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

    private static boolean degradeToFile(LogCollectContext context, List<String> lines) {
        Object fileManagerObj = context.getAttribute("__degradeFileManager");
        if (!(fileManagerObj instanceof DegradeFileManager)) {
            return false;
        }
        DegradeFileManager fileManager = (DegradeFileManager) fileManagerObj;
        if (!fileManager.isInitialized()) {
            return false;
        }
        List<String> copy = lines == null ? new ArrayList<String>() : lines;
        fileManager.write(context.getTraceId(), copy);
        return true;
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
        Object metrics = context.getAttribute("__metrics");
        if (metrics == null || context.getConfig() == null || !context.getConfig().isEnableMetrics()) {
            return;
        }
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : metrics.getClass().getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                Class<?>[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (args[i] == null) {
                        continue;
                    }
                    if (!wrap(paramTypes[i]).isAssignableFrom(wrap(args[i].getClass()))) {
                        continue MethodLoop;
                    }
                }
                method.invoke(metrics, args);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
