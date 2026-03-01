package com.logcollect.core.buffer;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.DegradeEvent;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SingleModeBuffer implements LogCollectBuffer {
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<LogEntry>();
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicLong bytes = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private final int maxCount;
    private final long maxBytes;
    private final GlobalBufferMemoryManager globalManager;

    public SingleModeBuffer(int maxCount, long maxBytes, GlobalBufferMemoryManager globalManager) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
    }

    @Override
    public boolean offer(LogCollectContext context, LogEntry entry) {
        if (entry == null) {
            return false;
        }
        if (closed.get()) {
            if (context != null) {
                context.incrementDiscardedCount();
            }
            notifyDegrade(context, "buffer_closed");
            return false;
        }

        long entryBytes = entry.estimateBytes();
        if (maxBytes > 0 && entryBytes > maxBytes) {
            if (context != null) {
                context.incrementDiscardedCount();
            }
            notifyDegrade(context, "buffer_entry_too_large");
            return false;
        }

        if (globalManager != null && !globalManager.tryAllocate(entryBytes)) {
            if (isHighLevel(entry.getLevel())) {
                globalManager.forceAllocate(entryBytes);
            } else {
                if (context != null) {
                    context.incrementDiscardedCount();
                }
                notifyDegrade(context, "global_memory_limit");
                return false;
            }
        }

        if (maxBytes > 0 && bytes.get() + entryBytes > maxBytes && count.get() > 0) {
            triggerFlush(context, false);
        }

        queue.offer(entry);
        count.incrementAndGet();
        bytes.addAndGet(entryBytes);
        if (context != null) {
            context.incrementCollectedCount();
            context.addCollectedBytes(entryBytes);
        }

        if (shouldFlush()) {
            triggerFlush(context, false);
        }
        updateUtilization(context);
        return true;
    }

    @Override
    public void triggerFlush(LogCollectContext context, boolean isFinal) {
        Runnable flushTask = () -> doTriggerFlush(context, isFinal);
        boolean asyncFlush = context != null
                && context.getConfig() != null
                && context.getConfig().isAsync()
                && !isFinal;
        if (asyncFlush) {
            AsyncFlushExecutor.submitOrRun(flushTask);
        } else {
            flushTask.run();
        }
    }

    @Override
    public void closeAndFlush(LogCollectContext context) {
        closed.set(true);
        triggerFlush(context, true);
    }

    private boolean shouldFlush() {
        return (maxCount > 0 && count.get() >= maxCount) || (maxBytes > 0 && bytes.get() >= maxBytes);
    }

    private void doTriggerFlush(LogCollectContext context, boolean isFinal) {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<LogEntry> batch = drain();
            if (!batch.isEmpty()) {
                String methodKey = context == null ? "unknown" : context.getMethodSignature();
                metricCall(context, "incrementFlush", methodKey, "SINGLE",
                        isFinal ? "final" : "threshold");
                flushBatch(context, batch);
                if (context != null) {
                    context.incrementFlushCount();
                }
            }
            updateUtilization(context);
        } finally {
            flushing.set(false);
        }
    }

    private List<LogEntry> drain() {
        List<LogEntry> batch = new ArrayList<LogEntry>();
        LogEntry entry;
        long drainedBytes = 0;
        while ((entry = queue.poll()) != null) {
            batch.add(entry);
            drainedBytes += entry.estimateBytes();
        }
        if (!batch.isEmpty()) {
            int remaining = count.addAndGet(-batch.size());
            if (remaining < 0) {
                count.set(0);
            }
            long remainingBytes = bytes.addAndGet(-drainedBytes);
            if (remainingBytes < 0) {
                bytes.set(0);
            }
            if (globalManager != null) {
                globalManager.release(drainedBytes);
            }
        }
        return batch;
    }

    private void flushBatch(LogCollectContext context, List<LogEntry> batch) {
        LogCollectHandler handler = context == null ? null : context.getHandler();
        String methodKey = context == null ? "unknown" : context.getMethodSignature();
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            if (context != null) {
                context.incrementDiscardedCount(batch.size());
            }
            notifyDegrade(context, "circuit_open");
            metricCall(context, "incrementDegradeTriggered", "circuit_open", methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, "circuit_open", toLines(batch), maxLevel(batch));
            }
            return;
        }
        for (LogEntry entry : batch) {
            try {
                if (handler != null) {
                    executeWithTx(context, () -> handler.appendLog(context, entry));
                }
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                metricCall(context, "incrementPersisted", methodKey, "SINGLE");
            } catch (Throwable t) {
                if (breaker != null) {
                    breaker.recordFailure();
                }
                if (context != null) {
                    context.incrementDiscardedCount();
                }
                notifyError(context, t, "appendLog");
                notifyDegrade(context, "handler_error");
                metricCall(context, "incrementPersistFailed", methodKey);
                metricCall(context, "incrementDegradeTriggered", "persist_failed", methodKey);
                if (context != null) {
                    java.util.List<String> lines = new java.util.ArrayList<String>();
                    lines.add(entry.getContent());
                    DegradeFallbackHandler.handleDegraded(context, "handler_error", lines, entry.getLevel());
                }
            }
        }
    }

    private LogCollectCircuitBreaker getBreaker(LogCollectContext context) {
        if (context == null) {
            return null;
        }
        Object breaker = context.getCircuitBreaker();
        return breaker instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) breaker : null;
    }

    private boolean isHighLevel(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
    }

    private void notifyDegrade(LogCollectContext context, String reason) {
        if (context == null) {
            return;
        }
        LogCollectHandler handler = context.getHandler();
        LogCollectConfig config = context.getConfig();
        if (handler == null || config == null) {
            return;
        }
        try {
            handler.onDegrade(context, new DegradeEvent(
                    context.getTraceId(),
                    context.getMethodSignature(),
                    reason,
                    config.getDegradeStorage(),
                    LocalDateTime.now()));
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onDegrade callback failed", t);
        }
    }

    private void notifyError(LogCollectContext context, Throwable error, String phase) {
        if (context == null) {
            return;
        }
        LogCollectHandler handler = context.getHandler();
        if (handler == null) {
            return;
        }
        try {
            handler.onError(context, error, phase);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onError callback failed", t);
        }
    }

    private java.util.List<String> toLines(List<LogEntry> entries) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        for (LogEntry entry : entries) {
            lines.add(entry.getContent());
        }
        return lines;
    }

    private String maxLevel(List<LogEntry> entries) {
        String max = "TRACE";
        for (LogEntry entry : entries) {
            max = higherLevel(max, entry.getLevel());
        }
        return max;
    }

    private String higherLevel(String left, String right) {
        return levelRank(right) > levelRank(left) ? right : left;
    }

    private int levelRank(String level) {
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

    private void metricCall(LogCollectContext context, String methodName, Object... args) {
        if (context == null || context.getConfig() == null || !context.getConfig().isEnableMetrics()) {
            return;
        }
        Object metrics = context.getAttribute("__metrics");
        if (metrics == null) {
            return;
        }
        invokeReflective(metrics, methodName, args);
    }

    private Object invokeReflective(Object target, String methodName, Object... args) {
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
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
                return method.invoke(target, args);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        return type;
    }

    private void executeWithTx(LogCollectContext context, Runnable action) {
        if (context == null || context.getConfig() == null || !context.getConfig().isTransactionIsolation()) {
            action.run();
            return;
        }
        Object txWrapper = context.getAttribute("__txWrapper");
        if (txWrapper == null) {
            action.run();
            return;
        }
        if (!invokeIfPresent(txWrapper, "executeInNewTransaction", action)) {
            action.run();
        }
    }

    private boolean invokeIfPresent(Object target, String methodName, Object... args) {
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
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
                method.invoke(target, args);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void updateUtilization(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isEnableMetrics()) {
            return;
        }
        if (maxCount <= 0 && maxBytes <= 0) {
            return;
        }
        double countRatio = maxCount <= 0 ? 0.0d : ((double) count.get() / (double) maxCount);
        double bytesRatio = maxBytes <= 0 ? 0.0d : ((double) bytes.get() / (double) maxBytes);
        double utilization = Math.max(countRatio, bytesRatio);
        if (utilization < 0.0d) {
            utilization = 0.0d;
        } else if (utilization > 1.0d) {
            utilization = 1.0d;
        }
        metricCall(context, "updateBufferUtilization", context.getMethodSignature(), utilization);
    }
}
