package com.logcollect.core.buffer;

import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.DegradeEvent;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.diagnostics.LogCollectDiag;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public class SingleModeBuffer implements LogCollectBuffer {
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<LogEntry>();
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
    private final BoundedBufferPolicy policy;
    private final ResilientFlusher resilientFlusher = new ResilientFlusher();

    public SingleModeBuffer(int maxCount, long maxBytes, GlobalBufferMemoryManager globalManager) {
        this(maxCount, maxBytes, globalManager,
                new BoundedBufferPolicy(maxBytes, maxCount, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY));
    }

    public SingleModeBuffer(int maxCount,
                            long maxBytes,
                            GlobalBufferMemoryManager globalManager,
                            BoundedBufferPolicy policy) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
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

        long entryBytes = entry.estimateBytes();
        if (maxBytes > 0 && entryBytes > maxBytes) {
            return handleOversizedEntry(context, entry, entryBytes);
        }

        if (!tryGlobalAllocate(context, entry, entryBytes)) {
            return false;
        }

        if (policy.getStrategy() == BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST && policy.isOverflow(entryBytes)) {
            evictOldestUntilFit(context, entryBytes);
        }

        BoundedBufferPolicy.RejectReason rejectReason = policy.beforeAdd(entryBytes, () -> triggerFlush(context, false));
        if (rejectReason != BoundedBufferPolicy.RejectReason.ACCEPTED) {
            if (globalManager != null) {
                globalManager.release(entryBytes);
            }
            if (policy.getStrategy() == BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST) {
                routeToDegradation(context, Collections.singletonList(entry), DegradeReason.BUFFER_OVERFLOW_REJECTED);
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
            LogCollectDiag.debug("Single buffer rejected due to overflow");
            return false;
        }

        count.increment();
        bytes.add(entryBytes);
        queue.offer(entry);
        if (context != null) {
            context.incrementCollectedCount();
            context.addCollectedBytes(entryBytes);
        }

        if (shouldFlush()) {
            triggerFlush(context, false);
        }
        updateUtilization(context);
        LogCollectDiag.debug("Buffer add: bytes=%d count=%d", currentBytes(), currentCount());
        return true;
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
        for (LogEntry entry : queue) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getContent());
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
                List<LogEntry> batch = drain();
                if (!batch.isEmpty()) {
                    if (warnOnly) {
                        int originalSize = batch.size();
                        batch = retainWarnOrAbove(batch);
                        int dropped = originalSize - batch.size();
                        if (dropped > 0 && context != null) {
                            context.incrementDiscardedCount(dropped);
                            resolveMetrics(context).incrementDiscarded(
                                    context.getMethodSignature(), "async_queue_full_low_level");
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    String methodKey = methodKey(context);
                    resolveMetrics(context).incrementFlush(
                            methodKey, "SINGLE", warnOnly ? "degraded" : (isFinal ? "final" : "threshold"));
                    flushBatch(context, batch, isFinal || warnOnly);
                    if (context != null) {
                        context.incrementFlushCount();
                    }
                    LogCollectDiag.debug("Flush triggered: segments=%d", batch.size());
                }
                updateUtilization(context);
                continueFlush = shouldFlush() && !queue.isEmpty();
            } while (continueFlush);
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
            int drainedCount = batch.size();
            count.add(-drainedCount);
            bytes.add(-drainedBytes);
            if (globalManager != null) {
                globalManager.release(drainedBytes);
            }
            policy.afterDrain(drainedBytes, drainedCount);
        }
        return batch;
    }

    private void flushBatch(LogCollectContext context, List<LogEntry> batch, boolean isFinal) {
        LogCollectHandler handler = context == null ? null : context.getHandler();
        String methodKey = methodKey(context);
        LogCollectMetrics metrics = resolveMetrics(context);
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            if (context != null) {
                context.incrementDiscardedCount(batch.size());
            }
            notifyDegrade(context, DegradeReason.CIRCUIT_OPEN.code());
            metrics.incrementDegradeTriggered(DegradeReason.CIRCUIT_OPEN.code(), methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, batch, DegradeReason.CIRCUIT_OPEN);
            }
            return;
        }

        Runnable writeAction = () -> {
            if (handler == null) {
                return;
            }
            TransactionExecutor txExecutor = resolveTransactionExecutor(context);
            for (LogEntry entry : batch) {
                txExecutor.executeInNewTransaction(() -> handler.appendLog(context, entry));
            }
        };
        Runnable onSuccess = () -> {
            if (breaker != null) {
                breaker.recordSuccess();
            }
            for (int i = 0; i < batch.size(); i++) {
                metrics.incrementPersisted(methodKey, "SINGLE");
            }
        };
        Runnable onExhausted = () -> {
            if (breaker != null) {
                breaker.recordFailure();
            }
            if (context != null) {
                context.incrementDiscardedCount(batch.size());
            }
            notifyDegrade(context, DegradeReason.PERSIST_FAILED.code());
            metrics.incrementPersistFailed(methodKey);
            metrics.incrementDegradeTriggered(DegradeReason.PERSIST_FAILED.code(), methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, batch, DegradeReason.PERSIST_FAILED);
            }
        };
        resilientFlusher.flushBatch(
                writeAction,
                onSuccess,
                onExhausted,
                () -> joinContents(batch),
                isFinal,
                resolveSyncRetryCapMs(context));
    }

    private boolean handleOversizedEntry(LogCollectContext context, LogEntry entry, long entryBytes) {
        boolean allocated = false;
        if (globalManager != null) {
            allocated = globalManager.tryAllocate(entryBytes);
            if (!allocated && isHighLevel(entry.getLevel())) {
                allocated = globalManager.forceAllocate(entryBytes);
                if (!allocated) {
                    resolveMetrics(context).incrementForceAllocateRejected(methodKey(context));
                }
            }
            if (!allocated) {
                routeToDegradation(context, Collections.singletonList(entry),
                        DegradeReason.OVERSIZED_GLOBAL_QUOTA_EXHAUSTED);
                resolveMetrics(context).incrementDirectFlush(methodKey(context), "degraded");
                return false;
            }
        }

        try {
            boolean flushed = directFlushEntry(context, entry);
            if (flushed) {
                if (context != null) {
                    context.incrementCollectedCount();
                    context.addCollectedBytes(entryBytes);
                }
                resolveMetrics(context).incrementDirectFlush(methodKey(context), "success");
                return true;
            }
            routeToDegradation(context, Collections.singletonList(entry), DegradeReason.OVERSIZED_FLUSH_FAILED);
            resolveMetrics(context).incrementDirectFlush(methodKey(context), "degraded");
            return false;
        } finally {
            if (allocated && globalManager != null) {
                globalManager.release(entryBytes);
            }
        }
    }

    private boolean directFlushEntry(LogCollectContext context, LogEntry entry) {
        LogCollectHandler handler = context == null ? null : context.getHandler();
        String methodKey = methodKey(context);
        LogCollectMetrics metrics = resolveMetrics(context);
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            return false;
        }
        Runnable writeAction = () -> {
            if (handler == null) {
                return;
            }
            TransactionExecutor txExecutor = resolveTransactionExecutor(context);
            txExecutor.executeInNewTransaction(() -> handler.appendLog(context, entry));
        };
        boolean success = resilientFlusher.flush(writeAction, entry::getContent, resolveSyncRetryCapMs(context));
        if (success) {
            if (breaker != null) {
                breaker.recordSuccess();
            }
            metrics.incrementPersisted(methodKey, "SINGLE");
        } else {
            if (breaker != null) {
                breaker.recordFailure();
            }
            metrics.incrementPersistFailed(methodKey);
        }
        return success;
    }

    private boolean tryGlobalAllocate(LogCollectContext context, LogEntry entry, long entryBytes) {
        if (globalManager == null) {
            return true;
        }
        if (globalManager.tryAllocate(entryBytes)) {
            return true;
        }
        if (isHighLevel(entry.getLevel())) {
            if (globalManager.forceAllocate(entryBytes)) {
                return true;
            }
            resolveMetrics(context).incrementForceAllocateRejected(methodKey(context));
            routeToDegradation(context, Collections.singletonList(entry), DegradeReason.GLOBAL_HARD_CEILING_REACHED);
            return false;
        }
        routeToDegradation(context, Collections.singletonList(entry), DegradeReason.GLOBAL_QUOTA_EXHAUSTED);
        return false;
    }

    private void evictOldestUntilFit(LogCollectContext context, long incomingBytes) {
        while (policy.isOverflow(incomingBytes)) {
            LogEntry evicted = queue.poll();
            if (evicted == null) {
                break;
            }
            long freedBytes = evicted.estimateBytes();
            count.add(-1L);
            bytes.add(-freedBytes);
            if (globalManager != null) {
                globalManager.release(freedBytes);
            }
            policy.afterDrain(freedBytes, 1);
            policy.recordDropped();
            routeToDegradation(context, Collections.singletonList(evicted), DegradeReason.BUFFER_OVERFLOW_EVICTED);
            LogCollectMetrics metrics = resolveMetrics(context);
            metrics.incrementBufferOverflow(methodKey(context), policy.getStrategy().name());
            metrics.incrementOverflowDegraded(methodKey(context), "drop_oldest");
            if (!policy.isOverflow(incomingBytes)) {
                break;
            }
        }
    }

    private void routeToDegradation(LogCollectContext context,
                                    List<LogEntry> entries,
                                    DegradeReason reason) {
        boolean degraded = false;
        try {
            degraded = DegradeFallbackHandler.handleDegraded(context, entries, reason);
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
            context.incrementDiscardedCount(entries == null ? 1 : Math.max(1, entries.size()));
            resolveMetrics(context).incrementDiscarded(methodKey(context), "degradation_exhausted");
        }
    }

    private List<LogEntry> retainWarnOrAbove(List<LogEntry> source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        List<LogEntry> kept = new ArrayList<LogEntry>(source.size());
        for (LogEntry entry : source) {
            if (entry != null && isHighLevel(entry.getLevel())) {
                kept.add(entry);
            }
        }
        return kept;
    }

    private List<String> toLines(List<LogEntry> entries) {
        List<String> lines = new ArrayList<String>(entries == null ? 0 : entries.size());
        if (entries == null) {
            return lines;
        }
        for (LogEntry entry : entries) {
            if (entry != null) {
                lines.add(entry.getContent());
            }
        }
        return lines;
    }

    private String maxLevel(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "TRACE";
        }
        String max = "TRACE";
        for (LogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
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

    private String toDiscardReason(BoundedBufferPolicy.RejectReason reason) {
        if (reason == BoundedBufferPolicy.RejectReason.GLOBAL_MEMORY_LIMIT) {
            return "global_memory_limit";
        }
        return "buffer_full";
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
        } catch (Exception t) {
            LogCollectInternalLogger.warn("onDegrade callback failed", t);
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

    private String joinContents(List<LogEntry> batch) {
        if (batch == null || batch.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(batch.size() * 64);
        for (LogEntry entry : batch) {
            if (entry == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getContent());
        }
        return sb.toString();
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
