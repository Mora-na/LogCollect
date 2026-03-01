package com.logcollect.core.buffer;

import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.*;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.diagnostics.LogCollectDiag;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
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
    private final BoundedBufferPolicy policy;
    private final ResilientFlusher resilientFlusher = new ResilientFlusher();

    private volatile int currentPatternVersion = LogLineDefaults.getVersion();

    private static class LogSegment {
        final String formattedLine;
        final String level;
        final long timestamp;
        final long estimatedBytes;
        final int patternVersion;

        LogSegment(String formattedLine,
                   String level,
                   long timestamp,
                   long estimatedBytes,
                   int patternVersion) {
            this.formattedLine = formattedLine;
            this.level = level;
            this.timestamp = timestamp;
            this.estimatedBytes = estimatedBytes;
            this.patternVersion = patternVersion;
        }
    }

    public AggregateModeBuffer(int maxCount,
                               long maxBytes,
                               GlobalBufferMemoryManager globalManager,
                               LogCollectHandler handler) {
        this(maxCount, maxBytes, globalManager, handler,
                new BoundedBufferPolicy(maxBytes, maxCount, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY));
    }

    public AggregateModeBuffer(int maxCount,
                               long maxBytes,
                               GlobalBufferMemoryManager globalManager,
                               LogCollectHandler handler,
                               BoundedBufferPolicy policy) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
        this.handler = handler;
        String sep = handler == null ? null : handler.aggregatedLogSeparator();
        this.separator = sep == null ? DEFAULT_SEPARATOR : sep;
        this.policy = policy == null
                ? new BoundedBufferPolicy(maxBytes, maxCount, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY)
                : policy;
    }

    @Override
    public boolean offer(LogCollectContext context, LogEntry entry) {
        if (entry == null) {
            return false;
        }
        if (closed.get()) {
            if (context != null) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", context.getMethodSignature(), "buffer_closed");
            }
            notifyDegrade(context, "buffer_closed");
            return false;
        }

        int patternVersion = LogLineDefaults.getVersion();
        if (patternVersion != currentPatternVersion && count.get() > 0) {
            triggerFlush(context, false);
            currentPatternVersion = patternVersion;
        }

        String formattedLine;
        try {
            formattedLine = handler == null ? entry.getContent() : handler.formatLogLine(entry);
        } catch (Throwable t) {
            notifyError(context, t, "formatLogLine");
            formattedLine = entry.getContent();
        }
        if (formattedLine == null || formattedLine.isEmpty()) {
            return false;
        }

        long lineBytes = estimateStringBytes(formattedLine);
        if (maxBytes > 0 && lineBytes > maxBytes) {
            if (context != null) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", context.getMethodSignature(), "buffer_entry_too_large");
            }
            notifyDegrade(context, "buffer_entry_too_large");
            return false;
        }

        if (globalManager != null && !globalManager.tryAllocate(lineBytes)) {
            if (isHighLevel(entry.getLevel())) {
                globalManager.forceAllocate(lineBytes);
            } else {
                if (context != null) {
                    context.incrementDiscardedCount();
                    metricCall(context, "incrementDiscarded", context.getMethodSignature(), "global_memory_limit");
                }
                notifyDegrade(context, "global_memory_limit");
                return false;
            }
        }

        if (policy.getStrategy() == BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST) {
            evictOldestUntilFit(context, lineBytes);
        }

        BoundedBufferPolicy.RejectReason rejectReason = policy.beforeAdd(lineBytes, () -> triggerFlush(context, false));
        if (rejectReason != BoundedBufferPolicy.RejectReason.ACCEPTED) {
            if (globalManager != null) {
                globalManager.release(lineBytes);
            }
            if (context != null) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded",
                        context.getMethodSignature(), toDiscardReason(rejectReason));
            }
            notifyDegrade(context, "buffer_overflow_drop_newest");
            return false;
        }

        LogSegment seg = new LogSegment(
                formattedLine,
                entry.getLevel(),
                entry.getTimestamp(),
                lineBytes,
                patternVersion);

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
        LogCollectDiag.debug("Buffer add: bytes=%d count=%d", bytes.get(), count.get());
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

    @Override
    public void forceFlush() {
        triggerFlush(null, true);
    }

    @Override
    public String dumpAsString() {
        StringBuilder sb = new StringBuilder();
        for (LogSegment segment : segments) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(segment.formattedLine);
        }
        return sb.toString();
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
                LogCollectDiag.debug("Flush triggered: segments=%d", agg.getEntryCount());
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
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(seg.timestamp), ZoneId.systemDefault());
            if (firstTime == null) {
                firstTime = time;
            }
            lastTime = time;
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
        policy.afterDrain(drainedBytes, drainedCount);

        return new AggregatedLog(
                sb.toString(),
                drainedCount,
                drainedBytes,
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
                        Collections.singletonList(agg.getContent()), agg.getMaxLevel());
            }
            return;
        }

        boolean success = resilientFlusher.flush(() -> {
            if (handler != null) {
                executeWithTx(context, () -> handler.flushAggregatedLog(context, agg));
            }
        }, agg::getContent);

        if (success) {
            if (breaker != null) {
                breaker.recordSuccess();
            }
            metricCall(context, "incrementPersisted", methodKey, "AGGREGATE");
            return;
        }

        if (breaker != null) {
            breaker.recordFailure();
        }
        if (context != null) {
            context.incrementDiscardedCount(agg.getEntryCount());
        }
        notifyDegrade(context, "handler_error");
        metricCall(context, "incrementPersistFailed", methodKey);
        metricCall(context, "incrementDegradeTriggered", "persist_failed", methodKey);
        if (context != null) {
            DegradeFallbackHandler.handleDegraded(context, "handler_error",
                    Collections.singletonList(agg.getContent()), agg.getMaxLevel());
        }
    }

    private void evictOldestUntilFit(LogCollectContext context, long incomingBytes) {
        while (policy.isOverflow(incomingBytes)) {
            LogSegment evicted = segments.poll();
            if (evicted == null) {
                break;
            }
            int remainingCount = count.decrementAndGet();
            if (remainingCount < 0) {
                count.set(0);
            }
            long remainingBytes = bytes.addAndGet(-evicted.estimatedBytes);
            if (remainingBytes < 0) {
                bytes.set(0);
            }
            if (globalManager != null) {
                globalManager.release(evicted.estimatedBytes);
            }
            policy.afterDrain(evicted.estimatedBytes, 1);
            policy.recordDropped();
            if (context != null) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", context.getMethodSignature(), "buffer_full");
            }
        }
    }

    private long estimateStringBytes(String value) {
        if (value == null) {
            return 0L;
        }
        return 48L + ((long) value.length() << 1);
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

    private String toDiscardReason(BoundedBufferPolicy.RejectReason reason) {
        if (reason == BoundedBufferPolicy.RejectReason.GLOBAL_MEMORY_LIMIT) {
            return "global_memory_limit";
        }
        return "buffer_full";
    }
}
