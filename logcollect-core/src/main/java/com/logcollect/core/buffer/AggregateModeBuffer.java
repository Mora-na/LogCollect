package com.logcollect.core.buffer;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.*;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AggregateModeBuffer implements LogCollectBuffer {
    private static final String DEFAULT_SEPARATOR = "\n";

    private final ConcurrentLinkedQueue<LogSegment> segments = new ConcurrentLinkedQueue<LogSegment>();
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicLong bytes = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private final int maxCount;
    private final long maxBytes;
    private final GlobalBufferMemoryManager globalManager;
    private final LogCollectHandler handler;
    private final String separator;

    private static class LogSegment {
        final String formattedLine;
        final String level;
        final LocalDateTime time;
        final long estimatedBytes;

        LogSegment(String formattedLine, String level, LocalDateTime time) {
            this.formattedLine = formattedLine;
            this.level = level;
            this.time = time;
            this.estimatedBytes = (formattedLine == null ? 0 : (long) formattedLine.length() * 2L) + 64;
        }
    }

    public AggregateModeBuffer(int maxCount,
                               long maxBytes,
                               GlobalBufferMemoryManager globalManager,
                               LogCollectHandler handler) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
        this.handler = handler;
        String sep = handler == null ? null : handler.aggregatedLogSeparator();
        this.separator = sep == null ? DEFAULT_SEPARATOR : sep;
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

        String formattedLine;
        try {
            formattedLine = handler == null ? entry.getContent() : handler.formatLogLine(entry);
        } catch (Throwable t) {
            notifyError(context, t, "formatLogLine");
            formattedLine = entry.getContent();
        }
        if (formattedLine == null) {
            formattedLine = "";
        }

        LogSegment seg = new LogSegment(formattedLine, entry.getLevel(), entry.getTime());
        if (maxBytes > 0 && seg.estimatedBytes > maxBytes) {
            if (context != null) {
                context.incrementDiscardedCount();
            }
            notifyDegrade(context, "buffer_entry_too_large");
            return false;
        }
        if (globalManager != null && !globalManager.tryAllocate(seg.estimatedBytes)) {
            if (isHighLevel(entry.getLevel())) {
                globalManager.forceAllocate(seg.estimatedBytes);
            } else {
                if (context != null) {
                    context.incrementDiscardedCount();
                }
                notifyDegrade(context, "global_memory_limit");
                return false;
            }
        }

        if (maxBytes > 0 && bytes.get() + seg.estimatedBytes > maxBytes && count.get() > 0) {
            triggerFlush(context, false);
        }

        segments.offer(seg);
        count.incrementAndGet();
        bytes.addAndGet(seg.estimatedBytes);
        if (context != null) {
            context.incrementCollectedCount();
            context.addCollectedBytes(seg.estimatedBytes);
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
            AggregatedLog agg = buildAggregatedLog(isFinal);
            if (agg != null) {
                String methodKey = context == null ? "unknown" : context.getMethodSignature();
                metricCall(context, "incrementFlush", methodKey, "AGGREGATE",
                        isFinal ? "final" : "threshold");
                flushAggregated(context, agg);
                if (context != null) {
                    context.incrementFlushCount();
                }
            }
            updateUtilization(context);
        } finally {
            flushing.set(false);
        }
    }

    private AggregatedLog buildAggregatedLog(boolean isFinal) {
        int estimatedChars = (int) Math.min(bytes.get() / 2L + 256L, Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder(estimatedChars);
        LogSegment seg;
        int drainedCount = 0;
        long drainedBytes = 0;
        String localMaxLevel = "TRACE";
        LocalDateTime firstTime = null;
        LocalDateTime lastTime = null;

        while ((seg = segments.poll()) != null) {
            if (drainedCount > 0) {
                sb.append(separator);
            }
            sb.append(seg.formattedLine);
            drainedCount++;
            drainedBytes += seg.estimatedBytes;
            if (firstTime == null) {
                firstTime = seg.time;
            }
            lastTime = seg.time;
            localMaxLevel = higherLevel(localMaxLevel, seg.level);
        }

        if (drainedCount == 0) {
            return null;
        }

        int remaining = count.addAndGet(-drainedCount);
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

        return new AggregatedLog(
                sb.toString(),
                drainedCount,
                (long) sb.length() * 2L,
                localMaxLevel,
                firstTime,
                lastTime,
                isFinal);
    }

    private void flushAggregated(LogCollectContext context, AggregatedLog agg) {
        String methodKey = context == null ? "unknown" : context.getMethodSignature();
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            if (context != null) {
                context.incrementDiscardedCount(agg.getEntryCount());
            }
            notifyDegrade(context, "circuit_open");
            metricCall(context, "incrementDegradeTriggered", "circuit_open", methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, "circuit_open",
                        java.util.Collections.singletonList(agg.getContent()), agg.getMaxLevel());
            }
            return;
        }
        try {
            if (handler != null) {
                executeWithTx(context, () -> handler.flushAggregatedLog(context, agg));
            }
            if (breaker != null) {
                breaker.recordSuccess();
            }
            metricCall(context, "incrementPersisted", methodKey, "AGGREGATE");
        } catch (Throwable t) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            if (context != null) {
                context.incrementDiscardedCount(agg.getEntryCount());
            }
            notifyError(context, t, "flushAggregatedLog");
            notifyDegrade(context, "handler_error");
            metricCall(context, "incrementPersistFailed", methodKey);
            metricCall(context, "incrementDegradeTriggered", "persist_failed", methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, "handler_error",
                        java.util.Collections.singletonList(agg.getContent()), agg.getMaxLevel());
            }
        }
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
        if ("TRACE".equals(v)) return 0;
        return 0;
    }

    private boolean isHighLevel(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
    }

    private LogCollectCircuitBreaker getBreaker(LogCollectContext context) {
        if (context == null) {
            return null;
        }
        Object breaker = context.getCircuitBreaker();
        return breaker instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) breaker : null;
    }

    private void notifyDegrade(LogCollectContext context, String reason) {
        if (context == null) {
            return;
        }
        LogCollectHandler h = context.getHandler();
        LogCollectConfig config = context.getConfig();
        if (h == null || config == null) {
            return;
        }
        try {
            h.onDegrade(context, new DegradeEvent(
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
        LogCollectHandler h = context.getHandler();
        if (h == null) {
            return;
        }
        try {
            h.onError(context, error, phase);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onError callback failed", t);
        }
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
