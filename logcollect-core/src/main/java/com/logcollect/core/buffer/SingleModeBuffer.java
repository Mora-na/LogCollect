package com.logcollect.core.buffer;

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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SingleModeBuffer implements LogCollectBuffer {
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<LogEntry>();
    // 条数使用 O(1) 计数器维护，避免 ConcurrentLinkedQueue.size() 的 O(n) 开销。
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicLong bytes = new AtomicLong(0);
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
            if (context != null) {
                context.incrementDiscardedCount();
                resolveMetrics(context).incrementDiscarded(context.getMethodSignature(), "buffer_entry_too_large");
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
                    resolveMetrics(context).incrementDiscarded(context.getMethodSignature(), "global_memory_limit");
                }
                notifyDegrade(context, "global_memory_limit");
                return false;
            }
        }

        if (policy.getStrategy() == BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST) {
            evictOldestUntilFit(context, entryBytes);
        }

        BoundedBufferPolicy.RejectReason rejectReason = policy.beforeAdd(entryBytes, () -> triggerFlush(context, false));
        if (rejectReason != BoundedBufferPolicy.RejectReason.ACCEPTED) {
            if (globalManager != null) {
                globalManager.release(entryBytes);
            }
            if (context != null) {
                context.incrementDiscardedCount();
                LogCollectMetrics metrics = resolveMetrics(context);
                metrics.incrementDiscarded(context.getMethodSignature(), toDiscardReason(rejectReason));
                metrics.incrementBufferOverflow(context.getMethodSignature(), policy.getStrategy().name());
            }
            notifyDegrade(context, "buffer_overflow_drop_newest");
            LogCollectDiag.debug("Single buffer drop newest");
            return false;
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
        LogCollectDiag.debug("Buffer add: bytes=%d count=%d", bytes.get(), count.get());
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
        return (maxCount > 0 && count.get() >= maxCount) || (maxBytes > 0 && bytes.get() >= maxBytes);
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
                    String methodKey = context == null ? "unknown" : context.getMethodSignature();
                    resolveMetrics(context).incrementFlush(
                            methodKey, "SINGLE", warnOnly ? "degraded" : (isFinal ? "final" : "threshold"));
                    flushBatch(context, batch, isFinal || warnOnly);
                    if (context != null) {
                        context.incrementFlushCount();
                    }
                    LogCollectDiag.debug("Flush triggered: segments=%d", batch.size());
                }
                updateUtilization(context);
                // 处理 flush 期间新增并再次达到阈值的日志，避免等待下一次入队触发。
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
        }
        return batch;
    }

    private void flushBatch(LogCollectContext context, List<LogEntry> batch, boolean isFinal) {
        LogCollectHandler handler = context == null ? null : context.getHandler();
        String methodKey = context == null ? "unknown" : context.getMethodSignature();
        LogCollectMetrics metrics = resolveMetrics(context);
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            if (context != null) {
                context.incrementDiscardedCount(batch.size());
            }
            notifyDegrade(context, "circuit_open");
            metrics.incrementDegradeTriggered("circuit_open", methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, "circuit_open", toLines(batch), maxLevel(batch));
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
            notifyDegrade(context, "persist_exhausted");
            metrics.incrementPersistFailed(methodKey);
            metrics.incrementDegradeTriggered("persist_exhausted", methodKey);
            if (context != null) {
                DegradeFallbackHandler.handleDegraded(context, "persist_exhausted", toLines(batch), maxLevel(batch));
            }
        };
        resilientFlusher.flushBatch(writeAction, onSuccess, onExhausted, () -> joinContents(batch), isFinal);
    }

    private void evictOldestUntilFit(LogCollectContext context, long incomingBytes) {
        while (policy.isOverflow(incomingBytes)) {
            int toDrop = Math.max(1, count.get() / 10);
            int actualDropped = 0;
            long freedBytes = 0L;
            for (int i = 0; i < toDrop; i++) {
                LogEntry evicted = queue.poll();
                if (evicted == null) {
                    break;
                }
                freedBytes += evicted.estimateBytes();
                actualDropped++;
                if (!policy.isOverflow(incomingBytes)) {
                    break;
                }
            }
            if (actualDropped == 0) {
                break;
            }
            int remainingCount = count.addAndGet(-actualDropped);
            if (remainingCount < 0) {
                count.set(0);
            }
            long remainingBytes = bytes.addAndGet(-freedBytes);
            if (remainingBytes < 0) {
                bytes.set(0);
            }
            if (globalManager != null) {
                globalManager.release(freedBytes);
            }
            policy.afterDrain(freedBytes, actualDropped);
            policy.recordDropped(actualDropped);
            if (context != null) {
                context.incrementDiscardedCount(actualDropped);
                LogCollectMetrics metrics = resolveMetrics(context);
                metrics.incrementDiscarded(context.getMethodSignature(), "buffer_full");
                metrics.incrementBufferOverflow(context.getMethodSignature(), policy.getStrategy().name());
            }
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

    private List<String> toLines(List<LogEntry> entries) {
        List<String> lines = new ArrayList<String>(entries.size());
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
        double countRatio = maxCount <= 0 ? 0.0d : ((double) count.get() / (double) maxCount);
        double bytesRatio = maxBytes <= 0 ? 0.0d : ((double) bytes.get() / (double) maxBytes);
        double utilization = Math.max(countRatio, bytesRatio);
        if (utilization < 0.0d) {
            utilization = 0.0d;
        } else if (utilization > 1.0d) {
            utilization = 1.0d;
        }
        resolveMetrics(context).updateBufferUtilization(context.getMethodSignature(), utilization);
    }

    private String toDiscardReason(BoundedBufferPolicy.RejectReason reason) {
        if (reason == BoundedBufferPolicy.RejectReason.GLOBAL_MEMORY_LIMIT) {
            return "global_memory_limit";
        }
        return "buffer_full";
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
