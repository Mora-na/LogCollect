package com.logcollect.core.buffer;

import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.format.LogLinePatternParser;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.*;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.diagnostics.LogCollectDiag;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.pipeline.SecurityPipeline;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 聚合模式日志缓冲实现。
 *
 * <p>在内存中缓存已格式化日志行，达到阈值或结束时聚合为一条批量日志交给
 * {@link LogCollectHandler#flushAggregatedLog(LogCollectContext, AggregatedLog)} 写出。
 */
public class AggregateModeBuffer implements LogCollectBuffer {
    private static final String DEFAULT_SEPARATOR = "\n";
    private static final long SEGMENT_OBJECT_OVERHEAD = 64L;

    private final ConcurrentLinkedQueue<LogSegment> segments = new ConcurrentLinkedQueue<LogSegment>();
    // 计数器采用 LongAdder 降低多线程 CAS 争用。
    @SuppressWarnings("unused")
    private long cPad1, cPad2, cPad3, cPad4, cPad5, cPad6, cPad7;
    private final LongAdder count = new LongAdder();
    @SuppressWarnings("unused")
    private long midPad1, midPad2, midPad3, midPad4, midPad5, midPad6, midPad7;
    private final LongAdder bytes = new LongAdder();
    @SuppressWarnings("unused")
    private long bPad1, bPad2, bPad3, bPad4, bPad5, bPad6, bPad7;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private final int maxCount;
    private final long maxBytes;
    private final GlobalBufferMemoryManager globalManager;
    private final LogCollectHandler handler;
    private final boolean canUseRawFormatFastPath;
    private final String separator;
    private final BoundedBufferPolicy policy;
    private final ResilientFlusher resilientFlusher = new ResilientFlusher();

    private final AtomicInteger patternVersion = new AtomicInteger(LogLineDefaults.getVersion());
    private static final ThreadLocal<StringBuilder> SB_HOLDER =
            ThreadLocal.withInitial(() -> new StringBuilder(4096));

    static class LogSegment {
        final String formattedLine;
        final String level;
        final long timestamp;
        final long estimatedBytes;
        final int patternVersion;

        LogSegment(String formattedLine,
                   String level,
                   long timestamp,
                   int patternVersion) {
            this.formattedLine = formattedLine;
            this.level = level;
            this.timestamp = timestamp;
            this.patternVersion = patternVersion;
            this.estimatedBytes = SEGMENT_OBJECT_OVERHEAD + estimateStringBytes(formattedLine);
        }

        // 兼容历史反射测试构造签名
        LogSegment(String formattedLine,
                   String level,
                   long timestamp,
                   long estimatedBytes,
                   int patternVersion) {
            this.formattedLine = formattedLine;
            this.level = level;
            this.timestamp = timestamp;
            this.patternVersion = patternVersion;
            this.estimatedBytes = estimatedBytes > 0
                    ? estimatedBytes
                    : (SEGMENT_OBJECT_OVERHEAD + estimateStringBytes(formattedLine));
        }

        long getEstimatedBytes() {
            return estimatedBytes;
        }

        String getFormattedLine() {
            return formattedLine;
        }

        String getLevel() {
            return level;
        }
    }

    /**
     * 创建聚合缓冲区（默认溢出策略为 {@code FLUSH_EARLY}）。
     */
    public AggregateModeBuffer(int maxCount,
                               long maxBytes,
                               GlobalBufferMemoryManager globalManager,
                               LogCollectHandler handler) {
        this(maxCount, maxBytes, globalManager, handler,
                new BoundedBufferPolicy(maxBytes, maxCount, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY));
    }

    /**
     * 创建聚合缓冲区。
     */
    public AggregateModeBuffer(int maxCount,
                               long maxBytes,
                               GlobalBufferMemoryManager globalManager,
                               LogCollectHandler handler,
                               BoundedBufferPolicy policy) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
        this.handler = handler;
        this.canUseRawFormatFastPath = detectRawFormatFastPath(handler);
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
                resolveMetrics(context).incrementDiscarded(context.getMethodSignature(), "buffer_closed");
            }
            notifyDegrade(context, "buffer_closed");
            return false;
        }

        int currentVersion = syncPatternVersion(context);

        String formattedLine;
        try {
            formattedLine = handler == null ? entry.getContent() : handler.formatLogLine(entry);
        } catch (Exception t) {
            notifyError(context, t, "formatLogLine");
            formattedLine = entry.getContent();
        } catch (Error e) {
            throw e;
        }
        return offerFormatted(context, entry.getLevel(), entry.getTimestamp(), formattedLine, currentVersion);
    }

    /**
     * AGGREGATE 专用：直接接收安全处理后的原始字段，减少中间对象构建。
     */
    public boolean offerRaw(LogCollectContext context, SecurityPipeline.ProcessedLogRecord record) {
        if (record == null) {
            return false;
        }
        if (closed.get()) {
            if (context != null) {
                context.incrementDiscardedCount();
                resolveMetrics(context).incrementDiscarded(context.getMethodSignature(), "buffer_closed");
            }
            notifyDegrade(context, "buffer_closed");
            return false;
        }

        int currentVersion = syncPatternVersion(context);

        String formattedLine;
        try {
            if (handler == null) {
                formattedLine = record.getContent();
            } else if (canUseRawFormatFastPath) {
                formattedLine = LogLinePatternParser.formatRaw(
                        record.getTraceId(),
                        record.getContent(),
                        record.getLevel(),
                        record.getTimestamp(),
                        record.getThreadName(),
                        record.getLoggerName(),
                        record.getThrowableString(),
                        record.getMdcContext(),
                        handler.logLinePattern());
            } else {
                formattedLine = handler.formatLogLine(record.toLogEntry());
            }
        } catch (Exception t) {
            notifyError(context, t, "formatLogLine");
            formattedLine = record.getContent();
        } catch (Error e) {
            throw e;
        }
        return offerFormatted(context, record.getLevel(), record.getTimestamp(), formattedLine, currentVersion);
    }

    private boolean offerFormatted(LogCollectContext context,
                                   String level,
                                   long timestamp,
                                   String formattedLine,
                                   int currentVersion) {
        if (formattedLine == null || formattedLine.isEmpty()) {
            return false;
        }

        LogSegment segment = new LogSegment(formattedLine, level, timestamp, currentVersion);
        long segmentBytes = segment.getEstimatedBytes();
        if (maxBytes > 0 && segmentBytes > maxBytes) {
            return handleOversizedSegment(context, segment);
        }

        if (!tryGlobalAllocate(context, segment, segmentBytes)) {
            return false;
        }

        if (policy.getStrategy() == BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST && policy.isOverflow(segmentBytes)) {
            evictOldestUntilFit(context, segmentBytes);
        }

        BoundedBufferPolicy.RejectReason rejectReason = policy.beforeAdd(segmentBytes, () -> triggerFlush(context, false));
        if (rejectReason != BoundedBufferPolicy.RejectReason.ACCEPTED) {
            if (globalManager != null) {
                globalManager.release(segmentBytes);
            }
            if (policy.getStrategy() == BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST) {
                routeToDegradation(context, segment.getFormattedLine(), segment.getLevel(),
                        DegradeReason.BUFFER_OVERFLOW_REJECTED);
                LogCollectMetrics metrics = resolveMetrics(context);
                metrics.incrementBufferOverflow(methodKey(context), policy.getStrategy().name());
                metrics.incrementOverflowDegraded(methodKey(context), "drop_newest");
            } else {
                if (context != null) {
                    context.incrementDiscardedCount();
                    LogCollectMetrics metrics = resolveMetrics(context);
                    metrics.incrementDiscarded(context.getMethodSignature(), "buffer_full");
                    metrics.incrementBufferOverflow(context.getMethodSignature(), policy.getStrategy().name());
                }
                notifyDegrade(context, "buffer_overflow");
            }
            return false;
        }

        count.increment();
        bytes.add(segment.getEstimatedBytes());
        segments.offer(segment);
        if (context != null) {
            context.incrementCollectedCount();
            context.addCollectedBytes(segment.getEstimatedBytes());
        }

        if (shouldFlush()) {
            triggerFlush(context, false);
        }
        updateUtilization(context);
        LogCollectDiag.debug("Buffer add: bytes=%d count=%d", currentBytes(), currentCount());
        return true;
    }

    private int syncPatternVersion(LogCollectContext context) {
        int currentVersion = LogLineDefaults.getVersion();
        int bufferedVersion = patternVersion.get();
        if (currentVersion != bufferedVersion
                && patternVersion.compareAndSet(bufferedVersion, currentVersion)
                && currentCount() > 0) {
            triggerFlush(context, false);
        }
        return currentVersion;
    }

    private boolean detectRawFormatFastPath(LogCollectHandler candidate) {
        if (candidate == null) {
            return false;
        }
        Class<?> candidateClass = candidate.getClass();
        if (isProxyLikeHandlerClass(candidateClass)) {
            return false;
        }
        try {
            Method method = candidateClass.getMethod("formatLogLine", LogEntry.class);
            return method.getDeclaringClass() == LogCollectHandler.class;
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isProxyLikeHandlerClass(Class<?> candidateClass) {
        if (candidateClass == null) {
            return false;
        }
        if (Proxy.isProxyClass(candidateClass)
                || candidateClass.isSynthetic()
                || candidateClass.isAnonymousClass()
                || candidateClass.isLocalClass()) {
            return true;
        }
        String name = candidateClass.getName();
        return name.contains("$$")
                || name.contains("Mockito")
                || name.contains("ByteBuddy")
                || name.contains("CGLIB");
    }

    @Override
    public void triggerFlush(LogCollectContext context, boolean isFinal) {
        boolean asyncFlush = context != null
                && context.getConfig() != null
                && context.getConfig().isAsync()
                && !isFinal;
        if (asyncFlush) {
            AsyncFlushExecutor.submitOrRun(new AsyncBufferFlushTask(context, false, false));
        } else {
            doTriggerFlush(context, isFinal, false);
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
        if (policy.getStrategy() != BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY) {
            return false;
        }
        return (maxCount > 0 && currentCount() >= maxCount) || (maxBytes > 0 && currentBytes() >= maxBytes);
    }

    private void doTriggerFlush(LogCollectContext context, boolean isFinal, boolean warnOnly) {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            boolean continueFlush;
            do {
                List<LogSegment> drained = drainSegments();
                if (!drained.isEmpty()) {
                    if (warnOnly) {
                        int originalSize = drained.size();
                        drained = retainWarnOrAbove(drained);
                        int dropped = originalSize - drained.size();
                        if (dropped > 0 && context != null) {
                            context.incrementDiscardedCount(dropped);
                            resolveMetrics(context).incrementDiscarded(
                                    context.getMethodSignature(), "async_queue_full_low_level");
                        }
                    }
                }
                if (!drained.isEmpty()) {
                    String methodKey = methodKey(context);
                    resolveMetrics(context).incrementFlush(
                            methodKey, "AGGREGATE", warnOnly ? "degraded" : (isFinal ? "final" : "threshold"));
                    List<List<LogSegment>> batches = splitByPatternVersion(drained);
                    for (List<LogSegment> batch : batches) {
                        AggregatedLog agg = buildAggregatedLog(batch, isFinal);
                        if (agg != null) {
                            flushAggregated(context, agg, isFinal || warnOnly);
                        }
                    }
                    if (context != null) {
                        context.incrementFlushCount();
                    }
                    LogCollectDiag.debug("Flush triggered: segments=%d", drained.size());
                }
                updateUtilization(context);
                continueFlush = shouldFlush() && !segments.isEmpty();
            } while (continueFlush);
        } finally {
            flushing.set(false);
        }
    }

    private List<LogSegment> drainSegments() {
        List<LogSegment> drained = new ArrayList<LogSegment>();
        LogSegment seg;
        long drainedBytes = 0L;
        while ((seg = segments.poll()) != null) {
            drained.add(seg);
            drainedBytes += seg.getEstimatedBytes();
        }
        if (!drained.isEmpty()) {
            int drainedCount = drained.size();
            count.add(-drainedCount);
            bytes.add(-drainedBytes);
            if (globalManager != null) {
                globalManager.release(drainedBytes);
            }
            policy.afterDrain(drainedBytes, drainedCount);
        }
        return drained;
    }

    private List<List<LogSegment>> splitByPatternVersion(List<LogSegment> drained) {
        if (drained == null || drained.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<LogSegment>> grouped = new ArrayList<List<LogSegment>>();
        List<LogSegment> currentBatch = null;
        int currentVersion = Integer.MIN_VALUE;
        for (LogSegment seg : drained) {
            if (currentBatch == null || seg.patternVersion != currentVersion) {
                currentBatch = new ArrayList<LogSegment>();
                grouped.add(currentBatch);
                currentVersion = seg.patternVersion;
            }
            currentBatch.add(seg);
        }
        return grouped;
    }

    private List<LogSegment> retainWarnOrAbove(List<LogSegment> source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        List<LogSegment> kept = new ArrayList<LogSegment>(source.size());
        for (LogSegment segment : source) {
            if (segment != null && isHighLevel(segment.level)) {
                kept.add(segment);
            }
        }
        return kept;
    }

    private AggregatedLog buildAggregatedLog(List<LogSegment> segmentsBatch, boolean isFinal) {
        if (segmentsBatch == null || segmentsBatch.isEmpty()) {
            return null;
        }
        int estimatedChars = 256;
        for (LogSegment segment : segmentsBatch) {
            estimatedChars += (int) Math.max(0, (segment.getEstimatedBytes() >> 1) + separator.length());
        }
        StringBuilder sb = SB_HOLDER.get();
        sb.setLength(0);
        sb.ensureCapacity(Math.min(estimatedChars, 4 * 1024 * 1024));

        int drainedCount = 0;
        long drainedBytes = 0;
        String localMaxLevel = "TRACE";
        LocalDateTime firstTime = null;
        LocalDateTime lastTime = null;

        for (LogSegment seg : segmentsBatch) {
            if (drainedCount > 0) {
                sb.append(separator);
            }
            sb.append(seg.formattedLine);
            drainedCount++;
            drainedBytes += seg.getEstimatedBytes();
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(seg.timestamp), ZoneId.systemDefault());
            if (firstTime == null) {
                firstTime = time;
            }
            lastTime = time;
            localMaxLevel = higherLevel(localMaxLevel, seg.level);
        }

        String content = sb.toString();
        if (sb.capacity() > 1024 * 1024) {
            SB_HOLDER.remove();
        }
        return new AggregatedLog(
                UUID.randomUUID().toString(),
                content,
                drainedCount,
                drainedBytes,
                localMaxLevel,
                firstTime,
                lastTime,
                isFinal);
    }

    private void flushAggregated(LogCollectContext context, AggregatedLog agg, boolean isFinal) {
        String methodKey = methodKey(context);
        LogCollectMetrics metrics = resolveMetrics(context);
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            if (context != null) {
                context.incrementDiscardedCount(agg.getEntryCount());
            }
            notifyDegrade(context, DegradeReason.CIRCUIT_OPEN.code());
            metrics.incrementDegradeTriggered(DegradeReason.CIRCUIT_OPEN.code(), methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, agg.getContent(), agg.getMaxLevel(), DegradeReason.CIRCUIT_OPEN);
            }
            return;
        }

        Runnable writeAction = () -> {
            if (handler == null) {
                return;
            }
            TransactionExecutor txExecutor = resolveTransactionExecutor(context);
            txExecutor.executeInNewTransaction(() -> handler.flushAggregatedLog(context, agg));
        };
        Runnable onSuccess = () -> {
            if (breaker != null) {
                breaker.recordSuccess();
            }
            metrics.incrementPersisted(methodKey, "AGGREGATE");
        };
        Runnable onExhausted = () -> {
            if (breaker != null) {
                breaker.recordFailure();
            }
            if (context != null) {
                context.incrementDiscardedCount(agg.getEntryCount());
            }
            notifyDegrade(context, DegradeReason.PERSIST_FAILED.code());
            metrics.incrementPersistFailed(methodKey);
            metrics.incrementDegradeTriggered(DegradeReason.PERSIST_FAILED.code(), methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, agg.getContent(), agg.getMaxLevel(), DegradeReason.PERSIST_FAILED);
            }
        };
        resilientFlusher.flushBatch(
                writeAction,
                onSuccess,
                onExhausted,
                agg::getContent,
                isFinal,
                resolveSyncRetryCapMs(context));
    }

    private boolean handleOversizedSegment(LogCollectContext context, LogSegment segment) {
        boolean allocated = false;
        if (globalManager != null) {
            allocated = globalManager.tryAllocate(segment.getEstimatedBytes());
            if (!allocated && isHighLevel(segment.getLevel())) {
                allocated = globalManager.forceAllocate(segment.getEstimatedBytes());
                if (!allocated) {
                    resolveMetrics(context).incrementForceAllocateRejected(methodKey(context));
                }
            }
            if (!allocated) {
                routeToDegradation(context, segment.getFormattedLine(), segment.getLevel(),
                        DegradeReason.OVERSIZED_GLOBAL_QUOTA_EXHAUSTED);
                resolveMetrics(context).incrementDirectFlush(methodKey(context), "degraded");
                return false;
            }
        }

        try {
            boolean flushed = directFlushSegment(context, segment);
            if (flushed) {
                if (context != null) {
                    context.incrementCollectedCount();
                    context.addCollectedBytes(segment.getEstimatedBytes());
                }
                resolveMetrics(context).incrementDirectFlush(methodKey(context), "success");
                return true;
            }
            routeToDegradation(context, segment.getFormattedLine(), segment.getLevel(), DegradeReason.OVERSIZED_FLUSH_FAILED);
            resolveMetrics(context).incrementDirectFlush(methodKey(context), "degraded");
            return false;
        } finally {
            if (allocated && globalManager != null) {
                globalManager.release(segment.getEstimatedBytes());
            }
        }
    }

    private boolean directFlushSegment(LogCollectContext context, LogSegment segment) {
        String methodKey = methodKey(context);
        LogCollectMetrics metrics = resolveMetrics(context);
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            return false;
        }
        AggregatedLog agg = new AggregatedLog(
                UUID.randomUUID().toString(),
                segment.getFormattedLine(),
                1,
                segment.getEstimatedBytes(),
                segment.getLevel(),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(segment.timestamp), ZoneId.systemDefault()),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(segment.timestamp), ZoneId.systemDefault()),
                false);
        Runnable writeAction = () -> {
            if (handler == null) {
                return;
            }
            TransactionExecutor txExecutor = resolveTransactionExecutor(context);
            txExecutor.executeInNewTransaction(() -> handler.flushAggregatedLog(context, agg));
        };
        boolean success = resilientFlusher.flush(writeAction, agg::getContent, resolveSyncRetryCapMs(context));
        if (success) {
            if (breaker != null) {
                breaker.recordSuccess();
            }
            metrics.incrementPersisted(methodKey, "AGGREGATE");
        } else {
            if (breaker != null) {
                breaker.recordFailure();
            }
            metrics.incrementPersistFailed(methodKey);
        }
        return success;
    }

    private boolean tryGlobalAllocate(LogCollectContext context, LogSegment segment, long segmentBytes) {
        if (globalManager == null) {
            return true;
        }
        if (globalManager.tryAllocate(segmentBytes)) {
            return true;
        }
        if (isHighLevel(segment.getLevel())) {
            if (globalManager.forceAllocate(segmentBytes)) {
                return true;
            }
            resolveMetrics(context).incrementForceAllocateRejected(methodKey(context));
            routeToDegradation(context, segment.getFormattedLine(), segment.getLevel(),
                    DegradeReason.GLOBAL_HARD_CEILING_REACHED);
            return false;
        }
        routeToDegradation(context, segment.getFormattedLine(), segment.getLevel(),
                DegradeReason.GLOBAL_QUOTA_EXHAUSTED);
        return false;
    }

    private void evictOldestUntilFit(LogCollectContext context, long incomingBytes) {
        while (policy.isOverflow(incomingBytes)) {
            LogSegment evicted = segments.poll();
            if (evicted == null) {
                break;
            }
            count.add(-1L);
            bytes.add(-evicted.getEstimatedBytes());
            if (globalManager != null) {
                globalManager.release(evicted.getEstimatedBytes());
            }
            policy.afterDrain(evicted.getEstimatedBytes(), 1);
            policy.recordDropped();
            routeToDegradation(context, evicted.getFormattedLine(), evicted.getLevel(),
                    DegradeReason.BUFFER_OVERFLOW_EVICTED);
            LogCollectMetrics metrics = resolveMetrics(context);
            metrics.incrementBufferOverflow(methodKey(context), policy.getStrategy().name());
            metrics.incrementOverflowDegraded(methodKey(context), "drop_oldest");
            if (!policy.isOverflow(incomingBytes)) {
                break;
            }
        }
    }

    private void routeToDegradation(LogCollectContext context,
                                    String formattedLine,
                                    String level,
                                    DegradeReason reason) {
        boolean degraded = false;
        try {
            degraded = DegradeFallbackHandler.handleDegraded(context, formattedLine, level, reason);
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Degradation failed: method={}, reason={}",
                    methodKey(context), reason == null ? "unknown" : reason.code(), e);
        } catch (Error e) {
            throw e;
        } finally {
            notifyDegrade(context, reason == null ? "unknown" : reason.code());
            resolveMetrics(context).incrementDegradeTriggered(
                    reason == null ? "unknown" : reason.code(), methodKey(context));
        }
        if (!degraded && context != null) {
            context.incrementDiscardedCount();
            resolveMetrics(context).incrementDiscarded(methodKey(context), "degradation_exhausted");
        }
    }

    static long estimateStringBytes(String value) {
        return LogEntry.estimateStringBytes(value);
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
        } catch (Exception t) {
            LogCollectInternalLogger.warn("onDegrade callback failed", t);
        } catch (Error e) {
            throw e;
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
        } catch (Exception t) {
            LogCollectInternalLogger.warn("onError callback failed", t);
        } catch (Error e) {
            throw e;
        }
    }

    private LogCollectMetrics resolveMetrics(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isEnableMetrics()) {
            return NoopLogCollectMetrics.INSTANCE;
        }
        LogCollectMetrics metrics = context.getAttribute("__metrics", LogCollectMetrics.class);
        return metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
    }

    private TransactionExecutor resolveTransactionExecutor(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isTransactionIsolation()) {
            return TransactionExecutor.DIRECT;
        }
        TransactionExecutor txExecutor = context.getAttribute("__txWrapper", TransactionExecutor.class);
        if (txExecutor != null) {
            return txExecutor;
        }
        return TransactionExecutor.DIRECT;
    }

    private void updateUtilization(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isEnableMetrics()) {
            return;
        }
        if (maxCount <= 0 && maxBytes <= 0) {
            return;
        }
        double countRatio = maxCount <= 0 ? 0.0d : ((double) currentCount() / (double) maxCount);
        double bytesRatio = maxBytes <= 0 ? 0.0d : ((double) currentBytes() / (double) maxBytes);
        double utilization = Math.max(countRatio, bytesRatio);
        if (utilization < 0.0d) {
            utilization = 0.0d;
        }
        resolveMetrics(context).updateBufferUtilization(context.getMethodSignature(), utilization);
    }

    private long currentCount() {
        long value = count.sum();
        return value < 0 ? 0 : value;
    }

    private long currentBytes() {
        long value = bytes.sum();
        return value < 0 ? 0 : value;
    }

    private String methodKey(LogCollectContext context) {
        return context == null ? "unknown" : context.getMethodSignature();
    }

    private long resolveSyncRetryCapMs(LogCollectContext context) {
        LogCollectConfig config = context == null ? null : context.getConfig();
        if (config == null) {
            return 200L;
        }
        return Math.max(1L, config.getFlushRetrySyncCapMs());
    }

    private final class AsyncBufferFlushTask implements AsyncFlushExecutor.RejectedAwareTask {
        private final LogCollectContext context;
        private final boolean isFinal;
        private final boolean warnOnly;

        private AsyncBufferFlushTask(LogCollectContext context, boolean isFinal, boolean warnOnly) {
            this.context = context;
            this.isFinal = isFinal;
            this.warnOnly = warnOnly;
        }

        @Override
        public void run() {
            doTriggerFlush(context, isFinal, warnOnly);
        }

        @Override
        public Runnable downgradeForRetry() {
            if (isFinal || warnOnly) {
                return null;
            }
            return new AsyncBufferFlushTask(context, isFinal, true);
        }

        @Override
        public void onDiscard(String reason) {
            if (context != null) {
                context.incrementDiscardedCount();
                resolveMetrics(context).incrementDiscarded(context.getMethodSignature(), reason);
            }
            notifyDegrade(context, reason);
        }
    }
}
