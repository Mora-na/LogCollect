package com.logcollect.core.pipeline;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.ResilientFlusher;
import com.logcollect.core.buffer.SingleWriterBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.SecurityComponentRegistry;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Pipeline Consumer：处理 RawLogRecord 并串行写入单写者 Buffer。
 */
public final class PipelineConsumer implements Runnable {

    public static final String ATTR_SECURITY_PIPELINE = "__securityPipeline";
    public static final String ATTR_SECURITY_METRICS = "__securityMetrics";

    private final String consumerName;
    private final ConcurrentLinkedQueue<LogCollectContext> assignedContexts =
            new ConcurrentLinkedQueue<LogCollectContext>();
    private final SecurityComponentRegistry securityRegistry;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ResilientFlusher resilientFlusher = new ResilientFlusher();

    private final StringBuilder formatBuilder = new StringBuilder(8192);
    private final ArrayList<LogEntry> drainList = new ArrayList<LogEntry>(256);

    private final AtomicLong idleLoops = new AtomicLong(0L);
    private final AtomicLong totalLoops = new AtomicLong(0L);

    public PipelineConsumer(String consumerName, SecurityComponentRegistry securityRegistry) {
        this.consumerName = consumerName;
        this.securityRegistry = securityRegistry;
    }

    public String consumerName() {
        return consumerName;
    }

    public void assign(LogCollectContext context) {
        if (context == null) {
            return;
        }
        assignedContexts.offer(context);
        context.setPipelineConsumer(this);
    }

    public void remove(LogCollectContext context) {
        if (context == null) {
            return;
        }
        assignedContexts.remove(context);
    }

    public void shutdown() {
        running.set(false);
    }

    public void awaitHandoff(LogCollectContext context, long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (context != null && context.isConsumerProcessing() && System.nanoTime() < deadline) {
            onSpinWaitCompat();
        }
    }

    public void closeAndFlush(LogCollectContext context, long handoffTimeoutNanos) {
        if (context == null) {
            return;
        }
        context.markClosing();
        SingleWriterBuffer buffer = asSingleWriterBuffer(context.getBuffer());
        if (buffer != null) {
            buffer.markClosing();
        }
        awaitHandoff(context, handoffTimeoutNanos);
        waitForFlushCompletion(buffer, handoffTimeoutNanos);

        PipelineQueue queue = asPipelineQueue(context.getPipelineQueue());
        if (queue != null) {
            RawLogRecord remaining;
            while ((remaining = queue.poll()) != null) {
                processOneRecord(context, remaining, true);
            }
        }

        flushIfNeeded(context, true);
        if (buffer != null) {
            buffer.markClosed();
        }
        context.markClosed();
        remove(context);
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            boolean idle = true;
            for (LogCollectContext context : assignedContexts) {
                if (context == null) {
                    continue;
                }
                if (context.isClosed()) {
                    assignedContexts.remove(context);
                    continue;
                }
                int processed = processContextBatch(context, 64);
                if (processed > 0) {
                    idle = false;
                }
            }

            long loops = totalLoops.incrementAndGet();
            if (idle) {
                idleLoops.incrementAndGet();
            }
            if ((loops & 0x3FFL) == 0L) {
                LogCollectMetrics metrics = resolveAnyMetrics();
                metrics.updatePipelineConsumerIdleRatio(consumerName, idleRatio());
            }

            if (idle) {
                LockSupport.parkNanos(100_000L);
            }
        }
    }

    private int processContextBatch(LogCollectContext context, int maxBatch) {
        if (context == null || context.isClosing()) {
            return 0;
        }
        PipelineQueue queue = asPipelineQueue(context.getPipelineQueue());
        if (queue == null) {
            return 0;
        }
        LogCollectMetrics metrics = resolveMetrics(context);
        metrics.updatePipelineQueueUtilization(context.getMethodSignature(), queue.utilization());

        int count = 0;
        for (int i = 0; i < maxBatch; i++) {
            Object timer = metrics.startPipelineProcessTimer();
            context.setConsumerProcessing(true);
            try {
                if (context.isClosing()) {
                    break;
                }
                RawLogRecord raw = queue.poll();
                if (raw == null) {
                    break;
                }
                processOneRecord(context, raw, false);
                count++;
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Pipeline consumer process error", e);
                context.incrementDiscardedCount();
                metrics.incrementDiscarded(context.getMethodSignature(), "pipeline_process_error");
            } catch (Error e) {
                throw e;
            } finally {
                context.setConsumerProcessing(false);
                metrics.stopPipelineProcessTimer(timer, context.getMethodSignature());
            }
        }
        return count;
    }

    private void processOneRecord(LogCollectContext context, RawLogRecord raw, boolean callerThreadMode) {
        if (context == null || raw == null || context.isClosed()) {
            return;
        }
        LogCollectConfig config = context.getConfig();
        LogCollectMetrics metrics = resolveMetrics(context);
        String methodKey = context.getMethodSignature();
        LogCollectHandler handler = context.getHandler();

        if (!callerThreadMode && context.isClosing()) {
            requeueForClosingHandoff(context, raw);
            return;
        }

        if (handler != null && !handler.shouldCollect(context, raw.level, raw.content)) {
            context.incrementDiscardedCount();
            metrics.incrementDiscarded(methodKey, "handler_filter");
            return;
        }

        if (isTotalLimitReached(context, config, 0L)) {
            context.incrementDiscardedCount();
            metrics.incrementDiscarded(methodKey, "total_limit_reached");
            return;
        }

        SecurityPipeline pipeline = resolveSecurityPipeline(context, config, methodKey, metrics);
        SecurityPipeline.SecurityMetrics securityMetrics = resolveSecurityMetrics(context, methodKey, metrics);
        long timeoutMs = config == null ? 50L : Math.max(1L, config.getSecurityPipelineTimeoutMs());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        SecurityPipeline.ProcessedLogRecord safe = pipeline.processRawRecordWithDeadline(
                context.getTraceId(),
                raw.content,
                raw.level,
                raw.timestamp,
                raw.threadName,
                raw.loggerName,
                raw.throwableString,
                raw.mdcCopy,
                securityMetrics,
                deadline);

        LogEntry entry = safe.toLogEntry();
        long entryBytes = entry.estimateBytes();
        if (isTotalLimitReached(context, config, entryBytes)) {
            context.incrementDiscardedCount();
            metrics.incrementDiscarded(methodKey, "total_limit_reached");
            return;
        }

        if (!callerThreadMode && context.isClosing()) {
            requeueForClosingHandoff(context, raw);
            return;
        }

        SingleWriterBuffer buffer = asSingleWriterBuffer(context.getBuffer());
        if (buffer == null || buffer.isClosed()) {
            context.incrementDiscardedCount();
            routeToDegradation(context, Collections.singletonList(entry), DegradeReason.BUFFER_CLOSED_LATE_ARRIVAL);
            return;
        }

        if (buffer.getMaxBytes() > 0 && entryBytes > buffer.getMaxBytes()) {
            handleOversizedEntry(context, entry, entryBytes);
            return;
        }

        if (!tryGlobalAllocate(context, entry, entryBytes)) {
            return;
        }
        boolean accepted = false;
        try {
            if (!callerThreadMode && context.isClosing()) {
                requeueForClosingHandoff(context, raw);
                return;
            }
            if (buffer.isClosed()) {
                routeToDegradation(context, Collections.singletonList(entry), DegradeReason.BUFFER_CLOSED_LATE_ARRIVAL);
                return;
            }
            buffer.offer(entry, entryBytes);
            accepted = true;
            context.incrementCollectedCount();
            context.addCollectedBytes(entryBytes);
            metrics.incrementCollected(methodKey, entry.getLevel(), context.getCollectMode().name());
        } finally {
            if (!accepted) {
                releaseGlobal(context, entryBytes);
            }
        }

        if (!callerThreadMode && buffer.shouldFlush()) {
            flushIfNeeded(context, false);
        }
    }

    private void requeueForClosingHandoff(LogCollectContext context, RawLogRecord raw) {
        PipelineQueue queue = asPipelineQueue(context.getPipelineQueue());
        if (queue != null && queue.forceOffer(raw)) {
            return;
        }
        context.incrementDiscardedCount();
        LogCollectMetrics metrics = resolveMetrics(context);
        metrics.incrementDiscarded(context.getMethodSignature(), "pipeline_handoff_requeue_failed");
        if (raw != null && isHighPriority(raw.level)) {
            DegradeFallbackHandler.handleDegraded(
                    context,
                    DegradeReason.PIPELINE_QUEUE_FULL.code(),
                    Collections.singletonList(raw.content),
                    raw.level);
        }
    }

    private void flushIfNeeded(LogCollectContext context, boolean isFinal) {
        SingleWriterBuffer buffer = asSingleWriterBuffer(context.getBuffer());
        if (buffer == null || buffer.isFlushing()) {
            return;
        }
        buffer.setFlushing(true);
        try {
            List<LogEntry> batch = buffer.drain(drainList);
            if (batch.isEmpty()) {
                return;
            }
            long drainedBytes = estimateBatchBytes(batch);
            try {
                flushBatchSync(context, batch, isFinal, true);
                context.incrementFlushCount();
                resolveMetrics(context).incrementFlush(
                        context.getMethodSignature(), context.getCollectMode().name(), isFinal ? "final" : "threshold");
            } finally {
                releaseGlobal(context, drainedBytes);
            }
        } finally {
            buffer.setFlushing(false);
        }
    }

    private void handleOversizedEntry(LogCollectContext context, LogEntry entry, long entryBytes) {
        if (!tryGlobalAllocate(context, entry, entryBytes)) {
            routeToDegradation(context, Collections.singletonList(entry), DegradeReason.OVERSIZED_GLOBAL_QUOTA_EXHAUSTED);
            resolveMetrics(context).incrementDirectFlush(context.getMethodSignature(), "degraded");
            return;
        }
        try {
            boolean success = flushBatchSync(context, Collections.singletonList(entry), false, false);
            if (success) {
                context.incrementCollectedCount();
                context.addCollectedBytes(entryBytes);
                resolveMetrics(context).incrementDirectFlush(context.getMethodSignature(), "success");
            } else {
                routeToDegradation(context, Collections.singletonList(entry), DegradeReason.OVERSIZED_FLUSH_FAILED);
                resolveMetrics(context).incrementDirectFlush(context.getMethodSignature(), "degraded");
            }
        } finally {
            releaseGlobal(context, entryBytes);
        }
    }

    private boolean flushBatchSync(LogCollectContext context,
                                   List<LogEntry> batch,
                                   boolean isFinal,
                                   boolean recordCircuitBreaker) {
        if (batch == null || batch.isEmpty()) {
            return true;
        }
        LogCollectHandler handler = context.getHandler();
        if (handler == null) {
            return true;
        }
        String methodKey = context.getMethodSignature();
        LogCollectMetrics metrics = resolveMetrics(context);
        LogCollectCircuitBreaker breaker = asBreaker(context.getCircuitBreaker());
        if (breaker != null && recordCircuitBreaker && !breaker.allowWrite()) {
            context.incrementDiscardedCount(batch.size());
            metrics.incrementDiscarded(methodKey, "circuit_open");
            metrics.incrementDegradeTriggered(DegradeReason.CIRCUIT_OPEN.code(), methodKey);
            routeToDegradation(context, batch, DegradeReason.CIRCUIT_OPEN);
            return false;
        }

        AtomicBoolean success = new AtomicBoolean(false);
        Runnable writeAction = () -> {
            if (context.getCollectMode() == CollectMode.AGGREGATE) {
                handler.flushAggregatedLog(context, buildAggregatedLog(handler, batch, isFinal));
            } else {
                TransactionExecutor executor = resolveTransactionExecutor(context);
                for (LogEntry entry : batch) {
                    executor.executeInNewTransaction(() -> handler.appendLog(context, entry));
                }
            }
        };
        Runnable onSuccess = () -> {
            success.set(true);
            if (breaker != null && recordCircuitBreaker) {
                breaker.recordSuccess();
            }
            if (context.getCollectMode() == CollectMode.AGGREGATE) {
                metrics.incrementPersisted(methodKey, CollectMode.AGGREGATE.name());
            } else {
                for (int i = 0; i < batch.size(); i++) {
                    metrics.incrementPersisted(methodKey, CollectMode.SINGLE.name());
                }
            }
        };
        Runnable onExhausted = () -> {
            if (breaker != null && recordCircuitBreaker) {
                breaker.recordFailure();
            }
            context.incrementDiscardedCount(batch.size());
            metrics.incrementPersistFailed(methodKey);
            metrics.incrementDegradeTriggered(DegradeReason.PERSIST_FAILED.code(), methodKey);
            routeToDegradation(context, batch, DegradeReason.PERSIST_FAILED);
        };
        resilientFlusher.flushBatch(writeAction, onSuccess, onExhausted, () -> joinContents(batch), true);
        return success.get();
    }

    private AggregatedLog buildAggregatedLog(LogCollectHandler handler, List<LogEntry> batch, boolean isFinal) {
        formatBuilder.setLength(0);
        String sep = handler.aggregatedLogSeparator();
        String separator = sep == null ? "\n" : sep;

        String maxLevel = "TRACE";
        long firstTs = Long.MAX_VALUE;
        long lastTs = 0L;
        for (LogEntry entry : batch) {
            String line;
            try {
                line = handler.formatLogLine(entry);
            } catch (Exception e) {
                line = entry.getContent();
            }
            if (line != null) {
                formatBuilder.append(line).append(separator);
            }
            maxLevel = higherLevel(maxLevel, entry.getLevel());
            long ts = entry.getTimestamp();
            if (ts > 0L) {
                firstTs = Math.min(firstTs, ts);
                lastTs = Math.max(lastTs, ts);
            }
        }
        long now = System.currentTimeMillis();
        if (firstTs == Long.MAX_VALUE) {
            firstTs = now;
        }
        if (lastTs <= 0L) {
            lastTs = firstTs;
        }
        return new AggregatedLog(
                UUID.randomUUID().toString(),
                formatBuilder.toString(),
                batch.size(),
                LogEntry.estimateStringBytes(formatBuilder.toString()),
                maxLevel,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(firstTs), ZoneId.systemDefault()),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(lastTs), ZoneId.systemDefault()),
                isFinal
        );
    }

    private boolean tryGlobalAllocate(LogCollectContext context, LogEntry entry, long entryBytes) {
        GlobalBufferMemoryManager manager = context.getAttribute("__globalBufferManager", GlobalBufferMemoryManager.class);
        if (manager == null || entryBytes <= 0L) {
            return true;
        }
        if (manager.tryAllocate(entryBytes)) {
            return true;
        }
        if (isHighPriority(entry.getLevel())) {
            if (manager.forceAllocate(entryBytes)) {
                return true;
            }
            resolveMetrics(context).incrementForceAllocateRejected(context.getMethodSignature());
            routeToDegradation(context, Collections.singletonList(entry), DegradeReason.GLOBAL_HARD_CEILING_REACHED);
            return false;
        }
        routeToDegradation(context, Collections.singletonList(entry), DegradeReason.GLOBAL_QUOTA_EXHAUSTED);
        return false;
    }

    private void releaseGlobal(LogCollectContext context, long bytes) {
        if (bytes <= 0L || context == null) {
            return;
        }
        GlobalBufferMemoryManager manager = context.getAttribute("__globalBufferManager", GlobalBufferMemoryManager.class);
        if (manager != null) {
            manager.release(bytes);
        }
    }

    private boolean isTotalLimitReached(LogCollectContext context, LogCollectConfig config, long nextBytes) {
        if (context == null || config == null) {
            return false;
        }
        if (config.getMaxTotalCollect() > 0 && context.getTotalCollectedCount() >= config.getMaxTotalCollect()) {
            return true;
        }
        return config.getMaxTotalCollectBytes() > 0
                && context.getTotalCollectedBytes() + Math.max(0L, nextBytes) > config.getMaxTotalCollectBytes();
    }

    private SecurityPipeline resolveSecurityPipeline(LogCollectContext context,
                                                     LogCollectConfig config,
                                                     String methodKey,
                                                     LogCollectMetrics metrics) {
        SecurityPipeline existing = context.getAttribute(ATTR_SECURITY_PIPELINE, SecurityPipeline.class);
        if (existing != null) {
            return existing;
        }
        com.logcollect.api.sanitizer.LogSanitizer sanitizer = null;
        com.logcollect.api.masker.LogMasker masker = null;
        if (securityRegistry != null) {
            sanitizer = securityRegistry.getSanitizer(config);
            masker = securityRegistry.getMasker(config);
        }
        if (sanitizer == null && config != null && config.isEnableSanitize()) {
            sanitizer = new DefaultLogSanitizer();
        }
        if (masker == null && config != null && config.isEnableMask()) {
            masker = new DefaultLogMasker();
        }
        SecurityPipeline created = new SecurityPipeline(sanitizer, masker);
        context.setAttribute(ATTR_SECURITY_PIPELINE, created);
        context.setAttribute(ATTR_SECURITY_METRICS, createSecurityMetrics(methodKey, metrics));
        return created;
    }

    private SecurityPipeline.SecurityMetrics resolveSecurityMetrics(LogCollectContext context,
                                                                    String methodKey,
                                                                    LogCollectMetrics metrics) {
        SecurityPipeline.SecurityMetrics existing =
                context.getAttribute(ATTR_SECURITY_METRICS, SecurityPipeline.SecurityMetrics.class);
        if (existing != null) {
            return existing;
        }
        SecurityPipeline.SecurityMetrics created = createSecurityMetrics(methodKey, metrics);
        context.setAttribute(ATTR_SECURITY_METRICS, created);
        return created;
    }

    private SecurityPipeline.SecurityMetrics createSecurityMetrics(String methodKey, LogCollectMetrics metrics) {
        LogCollectMetrics safeMetrics = metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
        return new SecurityPipeline.SecurityMetrics() {
            @Override
            public void onContentSanitized() {
                safeMetrics.incrementSanitizeHits(methodKey);
            }

            @Override
            public void onThrowableSanitized() {
                safeMetrics.incrementSanitizeHits(methodKey);
            }

            @Override
            public void onContentMasked() {
                safeMetrics.incrementMaskHits(methodKey);
            }

            @Override
            public void onThrowableMasked() {
                safeMetrics.incrementMaskHits(methodKey);
            }

            @Override
            public void onFastPathHit() {
                safeMetrics.incrementFastPathHits(methodKey);
            }

            @Override
            public void onPipelineTimeout(String step) {
                safeMetrics.incrementPipelineTimeout(methodKey, step);
            }
        };
    }

    private void routeToDegradation(LogCollectContext context, List<LogEntry> entries, DegradeReason reason) {
        if (context == null) {
            return;
        }
        DegradeReason safeReason = reason == null ? DegradeReason.PERSIST_FAILED : reason;
        resolveMetrics(context).incrementDegradeTriggered(safeReason.code(), context.getMethodSignature());
        DegradeFallbackHandler.handleDegraded(context, entries, safeReason);
    }

    private String joinContents(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(entries.size() * 64);
        for (LogEntry entry : entries) {
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

    private long estimateBatchBytes(List<LogEntry> entries) {
        long total = 0L;
        if (entries == null) {
            return total;
        }
        for (LogEntry entry : entries) {
            if (entry != null) {
                total += entry.estimateBytes();
            }
        }
        return total;
    }

    private LogCollectCircuitBreaker asBreaker(Object value) {
        return value instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) value : null;
    }

    private PipelineQueue asPipelineQueue(Object value) {
        return value instanceof PipelineQueue ? (PipelineQueue) value : null;
    }

    private SingleWriterBuffer asSingleWriterBuffer(Object value) {
        return value instanceof SingleWriterBuffer ? (SingleWriterBuffer) value : null;
    }

    private LogCollectMetrics resolveMetrics(LogCollectContext context) {
        if (context == null) {
            return NoopLogCollectMetrics.INSTANCE;
        }
        LogCollectMetrics metrics = context.getAttribute("__metrics", LogCollectMetrics.class);
        return metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
    }

    private LogCollectMetrics resolveAnyMetrics() {
        for (LogCollectContext context : assignedContexts) {
            LogCollectMetrics metrics = resolveMetrics(context);
            if (!(metrics instanceof NoopLogCollectMetrics)) {
                return metrics;
            }
        }
        return NoopLogCollectMetrics.INSTANCE;
    }

    private TransactionExecutor resolveTransactionExecutor(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isTransactionIsolation()) {
            return TransactionExecutor.DIRECT;
        }
        Object tx = context.getAttribute("__txWrapper");
        return tx instanceof TransactionExecutor ? (TransactionExecutor) tx : TransactionExecutor.DIRECT;
    }

    private boolean isHighPriority(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
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

    private double idleRatio() {
        long total = totalLoops.get();
        if (total <= 0L) {
            return 0.0d;
        }
        return (double) idleLoops.get() / (double) total;
    }

    private void waitForFlushCompletion(SingleWriterBuffer buffer, long timeoutNanos) {
        if (buffer == null || timeoutNanos <= 0L) {
            return;
        }
        long deadline = System.nanoTime() + timeoutNanos;
        while (buffer.isFlushing() && System.nanoTime() < deadline) {
            onSpinWaitCompat();
        }
    }

    private void onSpinWaitCompat() {
        try {
            Thread.class.getMethod("onSpinWait").invoke(null);
        } catch (Exception ignored) {
            Thread.yield();
        }
    }
}
