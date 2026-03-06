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
import com.logcollect.core.buffer.*;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.SecurityComponentRegistry;
import com.logcollect.core.util.SpinWaitHint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Pipeline Consumer：消费 RingBuffer 并串行刷写。
 */
public final class PipelineConsumer implements Runnable {

    public static final String ATTR_SECURITY_PIPELINE = "__securityPipeline";
    public static final String ATTR_SECURITY_METRICS = "__securityMetrics";

    private static final String ATTR_CLOSE_LATCH = "__pipelineCloseLatch";
    private static final String ATTR_AGGREGATE_DIRECT_BUFFER = "__aggregateDirectBuffer";
    private static final String ATTR_MEMORY_ACCOUNTANT = "__memoryAccountant";
    private static final int DEFAULT_DRAIN_BATCH = 64;
    private static final int DEFAULT_SPIN_THRESHOLD = 100;
    private static final int DEFAULT_YIELD_THRESHOLD = 200;

    private final String consumerName;
    private final ConcurrentHashMap<LogCollectContext, Boolean> activeContexts =
            new ConcurrentHashMap<LogCollectContext, Boolean>();
    private final ConsumerReadyQueue readyQueue = new ConsumerReadyQueue();
    private final AdaptiveIdleStrategy adaptiveIdleStrategy = new AdaptiveIdleStrategy();
    private final SecurityComponentRegistry securityRegistry;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ResilientFlusher resilientFlusher = new ResilientFlusher();

    private final MutableProcessedLogRecord processed = new MutableProcessedLogRecord();
    private final ArrayList<LogEntry> drainList = new ArrayList<LogEntry>(256);

    private final AtomicLong idleLoops = new AtomicLong(0L);
    private final AtomicLong totalLoops = new AtomicLong(0L);

    private volatile Thread workerThread;
    private volatile int spinThreshold = DEFAULT_SPIN_THRESHOLD;
    private volatile int yieldThreshold = DEFAULT_YIELD_THRESHOLD;

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
        activeContexts.put(context, Boolean.TRUE);
        context.setPipelineConsumer(this);
        refreshIdleThresholds(context);
        signal(context);
    }

    public void remove(LogCollectContext context) {
        if (context == null) {
            return;
        }
        activeContexts.remove(context);
        context.clearPipelineReady();
    }

    public void shutdown() {
        running.set(false);
        Thread worker = workerThread;
        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }

    public void signal(LogCollectContext context) {
        readyQueue.signal(context);
        Thread worker = workerThread;
        if (worker != null) {
            LockSupport.unpark(worker);
        }
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

        CountDownLatch latch = new CountDownLatch(1);
        context.setAttribute(ATTR_CLOSE_LATCH, latch);

        signal(context);

        boolean closedByWorker = false;
        try {
            if (handoffTimeoutNanos > 0L) {
                closedByWorker = latch.await(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(handoffTimeoutNanos)),
                        TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!closedByWorker && !context.isClosed()) {
            drainAndClose(context);
        }
    }

    @Override
    public void run() {
        workerThread = Thread.currentThread();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            boolean didWork = false;
            LogCollectContext context = readyQueue.poll();
            if (context != null) {
                context.clearPipelineReady();
                if (context.isClosed()) {
                    remove(context);
                } else if (context.isClosing()) {
                    drainAndClose(context);
                    didWork = true;
                    adaptiveIdleStrategy.reset();
                } else {
                    refreshIdleThresholds(context);
                    int processedCount = processContextBatch(context);
                    if (processedCount > 0) {
                        didWork = true;
                        adaptiveIdleStrategy.reset();
                    }
                    PipelineRingBuffer ringBuffer = asRingBuffer(context.getPipelineQueue());
                    if (ringBuffer != null
                            && (context.isClosing() || ringBuffer.hasAvailable() || ringBuffer.hasOverflow())) {
                        signal(context);
                    }
                }
            }

            long loops = totalLoops.incrementAndGet();
            if (!didWork) {
                idleLoops.incrementAndGet();
            }
            if ((loops & 0x3FFL) == 0L) {
                resolveAnyMetrics().updatePipelineConsumerIdleRatio(consumerName, idleRatio());
            }

            if (!didWork) {
                if (processClosingContexts()) {
                    adaptiveIdleStrategy.reset();
                    continue;
                }
                adaptiveIdleStrategy.idle(spinThreshold, yieldThreshold);
            }
        }
    }

    private int processContextBatch(LogCollectContext context) {
        if (context == null) {
            return 0;
        }
        PipelineRingBuffer ringBuffer = asRingBuffer(context.getPipelineQueue());
        if (ringBuffer == null) {
            return 0;
        }
        int maxBatch = resolveDrainBatch(context);

        LogCollectMetrics metrics = resolveMetrics(context);
        String methodKey = context.getMethodSignature();
        metrics.updatePipelineQueueUtilization(methodKey, ringBuffer.utilization());

        int count = 0;
        while (count < maxBatch && !context.isClosing()) {
            RawLogRecord overflow = ringBuffer.pollOverflow();
            if (overflow == null) {
                break;
            }
            Object timer = metrics.startPipelineProcessTimer();
            context.setConsumerProcessing(true);
            try {
                processOverflowRecord(context, overflow, false);
                count++;
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Pipeline consumer process error", e);
                context.incrementDiscardedCount();
                metrics.incrementDiscarded(methodKey, "pipeline_process_error");
            } catch (Error e) {
                throw e;
            } finally {
                context.setConsumerProcessing(false);
                metrics.stopPipelineProcessTimer(timer, methodKey);
            }
        }

        if (!context.isClosing() && count < maxBatch) {
            int ringBatch = Math.min(maxBatch - count, ringBuffer.availableCount(maxBatch - count));
            if (ringBatch > 0) {
                long baseSeq = ringBuffer.consumerSequence();
                int processed = 0;
                for (int i = 0; i < ringBatch; i++) {
                    long sequence = baseSeq + i;
                    MutableRawLogRecord slot = ringBuffer.getSlot(sequence);
                    Object timer = metrics.startPipelineProcessTimer();
                    context.setConsumerProcessing(true);
                    try {
                        processSlotRecord(context, slot, false);
                        ringBuffer.markConsumed(sequence);
                        processed++;
                    } catch (Exception e) {
                        LogCollectInternalLogger.warn("Pipeline consumer process error", e);
                        context.incrementDiscardedCount();
                        metrics.incrementDiscarded(methodKey, "pipeline_process_error");
                    } catch (Error e) {
                        throw e;
                    } finally {
                        context.setConsumerProcessing(false);
                        metrics.stopPipelineProcessTimer(timer, methodKey);
                    }
                }
                if (processed > 0) {
                    ringBuffer.advanceConsumerBy(processed);
                    count += processed;
                }
            }
        }
        return count;
    }

    private void processSlotRecord(LogCollectContext context, MutableRawLogRecord slot, boolean finalMode) {
        processRecord(context,
                slot.traceId,
                slot.formattedMessage,
                slot.level,
                slot.timestamp,
                slot.threadName,
                slot.loggerName,
                slot.throwableString,
                slot.mdcContext,
                finalMode);
    }

    private void processOverflowRecord(LogCollectContext context, RawLogRecord overflow, boolean finalMode) {
        processRecord(context,
                context.getTraceId(),
                overflow.content,
                overflow.level,
                overflow.timestamp,
                overflow.threadName,
                overflow.loggerName,
                overflow.throwableString,
                overflow.mdcCopy,
                finalMode);
    }

    private void processRecord(LogCollectContext context,
                               String traceId,
                               String content,
                               String level,
                               long timestamp,
                               String threadName,
                               String loggerName,
                               String throwable,
                               java.util.Map<String, String> mdc,
                               boolean finalMode) {
        if (context == null || context.isClosed()) {
            return;
        }

        LogCollectConfig config = context.getConfig();
        LogCollectMetrics metrics = resolveMetrics(context);
        String methodKey = context.getMethodSignature();
        LogCollectHandler handler = context.getHandler();

        if (handler != null && !handler.shouldCollect(context, level, messageSummary(content))) {
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
        pipeline.processRawInto(
                traceId,
                content,
                level,
                timestamp,
                threadName,
                loggerName,
                throwable,
                mdc,
                securityMetrics,
                deadline,
                processed);

        CollectMode mode = context.getCollectMode();
        if (mode == CollectMode.AGGREGATE) {
            appendToAggregate(context, processed, finalMode);
        } else {
            appendToSingle(context, processed, finalMode);
        }
    }

    @SuppressWarnings("unused")
    private void processOneRecord(LogCollectContext context, RawLogRecord raw, boolean callerThreadMode) {
        if (raw == null) {
            return;
        }
        if (!callerThreadMode && context != null && context.isClosing()) {
            requeueForClosingHandoff(context, raw);
            return;
        }
        processRecord(context,
                context == null ? null : context.getTraceId(),
                raw.content,
                raw.level,
                raw.timestamp,
                raw.threadName,
                raw.loggerName,
                raw.throwableString,
                raw.mdcCopy,
                callerThreadMode);
    }

    @SuppressWarnings("unused")
    private void requeueForClosingHandoff(LogCollectContext context, RawLogRecord raw) {
        if (context == null || raw == null) {
            return;
        }
        PipelineRingBuffer ringBuffer = asRingBuffer(context.getPipelineQueue());
        if (ringBuffer != null && ringBuffer.offerOverflow(raw)) {
            return;
        }
        context.incrementDiscardedCount();
        resolveMetrics(context).incrementDiscarded(context.getMethodSignature(), "pipeline_handoff_requeue_failed");
        if (isHighPriority(raw.level)) {
            DegradeFallbackHandler.handleDegraded(
                    context,
                    DegradeReason.PIPELINE_QUEUE_FULL.code(),
                    Collections.singletonList(raw.content),
                    raw.level);
        }
    }

    private void appendToSingle(LogCollectContext context,
                                MutableProcessedLogRecord record,
                                boolean finalMode) {
        LogCollectConfig config = context.getConfig();
        LogCollectMetrics metrics = resolveMetrics(context);
        String methodKey = context.getMethodSignature();

        LogEntry entry = record.toLogEntry();
        long entryBytes = entry.estimateBytes();
        if (isTotalLimitReached(context, config, entryBytes)) {
            context.incrementDiscardedCount();
            metrics.incrementDiscarded(methodKey, "total_limit_reached");
            return;
        }

        SingleWriterBuffer buffer = asSingleWriterBuffer(context.getBuffer());
        if (buffer == null || buffer.isClosed()) {
            context.incrementDiscardedCount();
            DegradeFallbackHandler.handleDegraded(context,
                    Collections.singletonList(entry),
                    DegradeReason.BUFFER_CLOSED_LATE_ARRIVAL);
            return;
        }

        if (buffer.getMaxBytes() > 0 && entryBytes > buffer.getMaxBytes()) {
            handleOversizedEntry(context, entry, entryBytes);
            return;
        }

        if (!tryAllocate(context, entryBytes, record.getLevel(), entry)) {
            return;
        }

        boolean accepted = false;
        try {
            buffer.offer(entry, entryBytes);
            accepted = true;
            context.incrementCollectedCount();
            context.addCollectedBytes(entryBytes);
            metrics.incrementCollected(methodKey, entry.getLevel(), CollectMode.SINGLE.name());
        } finally {
            if (!accepted) {
                releaseGlobal(context, entryBytes);
            }
        }

        if (!finalMode && buffer.shouldFlush()) {
            flushSingleBuffer(context, false);
        }
    }

    private void appendToAggregate(LogCollectContext context,
                                   MutableProcessedLogRecord record,
                                   boolean finalMode) {
        LogCollectConfig config = context.getConfig();
        LogCollectMetrics metrics = resolveMetrics(context);
        String methodKey = context.getMethodSignature();

        long estimatedBytes = estimateAggregateBytes(record);
        if (isTotalLimitReached(context, config, estimatedBytes)) {
            context.incrementDiscardedCount();
            metrics.incrementDiscarded(methodKey, "total_limit_reached");
            return;
        }

        String linePattern = resolveLinePattern(context.getHandler());
        AggregateDirectBuffer aggregateBuffer = resolveAggregateBuffer(context, config);

        int currentPatternVersion = com.logcollect.api.format.LogLineDefaults.getVersion();
        if (currentPatternVersion != aggregateBuffer.getPatternVersion()) {
            if (aggregateBuffer.hasEntries()) {
                flushAggregateBuffer(context, false);
            }
            aggregateBuffer.setPatternVersion(currentPatternVersion);
        }

        if (!tryAllocateAggregate(context, estimatedBytes, record.getLevel(),
                record.getProcessedMessage(), record.getTraceId())) {
            return;
        }

        boolean appended = false;
        try {
            aggregateBuffer.append(record, linePattern);
            appended = true;
            context.incrementCollectedCount();
            context.addCollectedBytes(estimatedBytes);
            metrics.incrementCollected(methodKey, record.getLevel(), CollectMode.AGGREGATE.name());
        } finally {
            if (!appended) {
                releaseGlobal(context, estimatedBytes);
            }
        }

        if (!finalMode && aggregateBuffer.shouldFlush()) {
            flushAggregateBuffer(context, false);
        }
    }

    private void flushSingleBuffer(LogCollectContext context, boolean finalFlush) {
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
            long batchBytes = estimateBatchBytes(batch);
            try {
                flushSingleBatch(context, batch, finalFlush, true);
                context.incrementFlushCount();
                resolveMetrics(context).incrementFlush(
                        context.getMethodSignature(),
                        CollectMode.SINGLE.name(),
                        finalFlush ? "final" : "threshold");
            } finally {
                releaseGlobal(context, batchBytes);
            }
        } finally {
            buffer.setFlushing(false);
        }
    }

    private void flushAggregateBuffer(LogCollectContext context, boolean finalFlush) {
        AggregateDirectBuffer aggregateBuffer = context.getAttribute(ATTR_AGGREGATE_DIRECT_BUFFER,
                AggregateDirectBuffer.class);
        if (aggregateBuffer == null || !aggregateBuffer.hasEntries()) {
            return;
        }

        AggregatedLog aggregated = aggregateBuffer.buildAndReset(finalFlush);
        if (aggregated == null) {
            return;
        }

        try {
            flushAggregated(context, aggregated, finalFlush, true);
            context.incrementFlushCount();
            resolveMetrics(context).incrementFlush(
                    context.getMethodSignature(),
                    CollectMode.AGGREGATE.name(),
                    finalFlush ? "final" : "threshold");
        } finally {
            releaseGlobal(context, aggregated.getTotalBytes());
        }
    }

    @SuppressWarnings("unused")
    private void flushIfNeeded(LogCollectContext context, boolean finalFlush) {
        if (context == null) {
            return;
        }
        if (context.getCollectMode() == CollectMode.AGGREGATE) {
            flushAggregateBuffer(context, finalFlush);
        } else {
            flushSingleBuffer(context, finalFlush);
        }
    }

    private boolean flushSingleBatch(LogCollectContext context,
                                     List<LogEntry> batch,
                                     boolean finalFlush,
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
            DegradeFallbackHandler.handleDegraded(context, batch, DegradeReason.CIRCUIT_OPEN);
            return false;
        }

        AtomicBoolean success = new AtomicBoolean(false);
        Runnable writeAction = () -> {
            TransactionExecutor executor = resolveTransactionExecutor(context);
            for (LogEntry entry : batch) {
                executor.executeInNewTransaction(() -> handler.appendLog(context, entry));
            }
        };
        Runnable onSuccess = () -> {
            success.set(true);
            if (breaker != null && recordCircuitBreaker) {
                breaker.recordSuccess();
            }
            for (int i = 0; i < batch.size(); i++) {
                metrics.incrementPersisted(methodKey, CollectMode.SINGLE.name());
            }
        };
        Runnable onExhausted = () -> {
            if (breaker != null && recordCircuitBreaker) {
                breaker.recordFailure();
            }
            context.incrementDiscardedCount(batch.size());
            metrics.incrementPersistFailed(methodKey);
            metrics.incrementDegradeTriggered(DegradeReason.PERSIST_FAILED.code(), methodKey);
            DegradeFallbackHandler.handleDegraded(context, batch, DegradeReason.PERSIST_FAILED);
        };

        resilientFlusher.flushBatch(
                writeAction,
                onSuccess,
                onExhausted,
                () -> joinContents(batch),
                finalFlush,
                resolveSyncRetryCapMs(context));
        return success.get();
    }

    @SuppressWarnings("unused")
    private boolean flushBatchSync(LogCollectContext context,
                                   List<LogEntry> batch,
                                   boolean finalFlush,
                                   boolean recordCircuitBreaker) {
        if (context != null && context.getCollectMode() == CollectMode.AGGREGATE) {
            AggregatedLog aggregated = buildAggregatedLog(batch, finalFlush);
            return flushAggregated(context, aggregated, finalFlush, recordCircuitBreaker);
        }
        return flushSingleBatch(context, batch, finalFlush, recordCircuitBreaker);
    }

    private boolean flushAggregated(LogCollectContext context,
                                    AggregatedLog aggregated,
                                    boolean finalFlush,
                                    boolean recordCircuitBreaker) {
        if (aggregated == null) {
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
            context.incrementDiscardedCount(Math.max(1, aggregated.getEntryCount()));
            metrics.incrementDiscarded(methodKey, "circuit_open");
            metrics.incrementDegradeTriggered(DegradeReason.CIRCUIT_OPEN.code(), methodKey);
            DegradeFallbackHandler.handleDegraded(context,
                    aggregated.getContent(),
                    aggregated.getMaxLevel(),
                    DegradeReason.CIRCUIT_OPEN);
            return false;
        }

        AtomicBoolean success = new AtomicBoolean(false);
        Runnable writeAction = () -> {
            TransactionExecutor executor = resolveTransactionExecutor(context);
            executor.executeInNewTransaction(() -> handler.flushAggregatedLog(context, aggregated));
        };
        Runnable onSuccess = () -> {
            success.set(true);
            if (breaker != null && recordCircuitBreaker) {
                breaker.recordSuccess();
            }
            metrics.incrementPersisted(methodKey, CollectMode.AGGREGATE.name());
        };
        Runnable onExhausted = () -> {
            if (breaker != null && recordCircuitBreaker) {
                breaker.recordFailure();
            }
            context.incrementDiscardedCount(Math.max(1, aggregated.getEntryCount()));
            metrics.incrementPersistFailed(methodKey);
            metrics.incrementDegradeTriggered(DegradeReason.PERSIST_FAILED.code(), methodKey);
            DegradeFallbackHandler.handleDegraded(context,
                    aggregated.getContent(),
                    aggregated.getMaxLevel(),
                    DegradeReason.PERSIST_FAILED);
        };

        resilientFlusher.flushBatch(
                writeAction,
                onSuccess,
                onExhausted,
                aggregated::getContent,
                finalFlush,
                resolveSyncRetryCapMs(context));
        return success.get();
    }

    private void handleOversizedEntry(LogCollectContext context, LogEntry entry, long entryBytes) {
        if (!tryAllocate(context, entryBytes, entry.getLevel(), entry)) {
            DegradeFallbackHandler.handleDegraded(context,
                    Collections.singletonList(entry),
                    DegradeReason.OVERSIZED_GLOBAL_QUOTA_EXHAUSTED);
            resolveMetrics(context).incrementDirectFlush(context.getMethodSignature(), "degraded");
            return;
        }

        try {
            boolean success = flushSingleBatch(context, Collections.singletonList(entry), false, false);
            if (success) {
                context.incrementCollectedCount();
                context.addCollectedBytes(entryBytes);
                resolveMetrics(context).incrementDirectFlush(context.getMethodSignature(), "success");
            } else {
                DegradeFallbackHandler.handleDegraded(context,
                        Collections.singletonList(entry),
                        DegradeReason.OVERSIZED_FLUSH_FAILED);
                resolveMetrics(context).incrementDirectFlush(context.getMethodSignature(), "degraded");
            }
        } finally {
            releaseGlobal(context, entryBytes);
        }
    }

    private void drainAndClose(LogCollectContext context) {
        if (context == null || context.isClosed()) {
            countDownCloseLatch(context);
            return;
        }

        PipelineRingBuffer ringBuffer = asRingBuffer(context.getPipelineQueue());
        LogCollectConfig config = context.getConfig();
        long timeoutMs = config == null ? 100L : Math.max(1L, config.getPipelineUnpublishedSlotTimeoutMs());
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long waitStart = 0L;

        if (ringBuffer != null) {
            while (true) {
                RawLogRecord overflow = ringBuffer.pollOverflow();
                if (overflow != null) {
                    processOverflowRecord(context, overflow, true);
                    waitStart = 0L;
                    continue;
                }

                MutableRawLogRecord slot = ringBuffer.tryConsume();
                if (slot != null) {
                    processSlotRecord(context, slot, true);
                    ringBuffer.advanceConsumer();
                    waitStart = 0L;
                    continue;
                }

                if (!ringBuffer.hasPending()) {
                    break;
                }

                if (waitStart == 0L) {
                    waitStart = System.nanoTime();
                }
                if (System.nanoTime() - waitStart >= timeoutNanos) {
                    ringBuffer.skipUnpublishedSlot();
                    context.incrementDiscardedCount();
                    resolveMetrics(context).incrementDiscarded(
                            context.getMethodSignature(),
                            "pipeline_unpublished_slot_timeout");
                    waitStart = 0L;
                    continue;
                }
                LockSupport.parkNanos(100_000L);
            }
        }

        flushSingleBuffer(context, true);
        flushAggregateBuffer(context, true);

        BatchedMemoryAccountant accountant = context.getAttribute(ATTR_MEMORY_ACCOUNTANT,
                BatchedMemoryAccountant.class);
        if (accountant != null) {
            accountant.close();
        }

        SingleWriterBuffer buffer = asSingleWriterBuffer(context.getBuffer());
        if (buffer != null) {
            buffer.markClosed();
        }
        context.markClosed();
        context.clearPipelineReady();
        remove(context);
        countDownCloseLatch(context);
    }

    private void countDownCloseLatch(LogCollectContext context) {
        if (context == null) {
            return;
        }
        CountDownLatch latch = context.getAttribute(ATTR_CLOSE_LATCH, CountDownLatch.class);
        if (latch != null) {
            latch.countDown();
        }
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

    private boolean tryAllocate(LogCollectContext context, long bytes, String level, LogEntry entry) {
        if (bytes <= 0L || context == null) {
            return true;
        }

        BatchedMemoryAccountant accountant = resolveMemoryAccountant(context);
        if (accountant == null) {
            return true;
        }

        if (accountant.allocate(bytes)) {
            return true;
        }

        LogCollectMetrics metrics = resolveMetrics(context);
        if (isHighPriority(level)) {
            if (accountant.forceAllocate(bytes)) {
                return true;
            }
            metrics.incrementForceAllocateRejected(context.getMethodSignature());
            if (entry != null) {
                DegradeFallbackHandler.handleDegraded(context,
                        Collections.singletonList(entry),
                        DegradeReason.GLOBAL_HARD_CEILING_REACHED);
            }
            return false;
        }

        if (entry != null) {
            DegradeFallbackHandler.handleDegraded(context,
                    Collections.singletonList(entry),
                    DegradeReason.GLOBAL_QUOTA_EXHAUSTED);
        }
        return false;
    }

    @SuppressWarnings("unused")
    private boolean tryGlobalAllocate(LogCollectContext context, LogEntry entry, long entryBytes) {
        return tryAllocate(context, entryBytes, entry == null ? null : entry.getLevel(), entry);
    }

    private boolean tryAllocateAggregate(LogCollectContext context,
                                         long bytes,
                                         String level,
                                         String content,
                                         String traceId) {
        if (bytes <= 0L || context == null) {
            return true;
        }

        BatchedMemoryAccountant accountant = resolveMemoryAccountant(context);
        if (accountant == null) {
            return true;
        }

        if (accountant.allocate(bytes)) {
            return true;
        }

        LogCollectMetrics metrics = resolveMetrics(context);
        if (isHighPriority(level)) {
            if (accountant.forceAllocate(bytes)) {
                return true;
            }
            metrics.incrementForceAllocateRejected(context.getMethodSignature());
            RawLogRecord overflow = new RawLogRecord(content, null, level, null, null,
                    System.currentTimeMillis(), Collections.emptyMap(), context);
            DegradeFallbackHandler.handleDegraded(context,
                    DegradeReason.GLOBAL_HARD_CEILING_REACHED.code(),
                    Collections.singletonList(overflow.content),
                    overflow.level);
            return false;
        }

        DegradeFallbackHandler.handleDegraded(context,
                DegradeReason.GLOBAL_QUOTA_EXHAUSTED.code(),
                Collections.singletonList(content == null ? "" : content),
                level);
        return false;
    }

    private void releaseGlobal(LogCollectContext context, long bytes) {
        if (bytes <= 0L || context == null) {
            return;
        }
        BatchedMemoryAccountant accountant = resolveMemoryAccountant(context);
        if (accountant != null) {
            accountant.release(bytes);
        }
    }

    @SuppressWarnings("unused")
    private void routeToDegradation(LogCollectContext context, List<LogEntry> entries, DegradeReason reason) {
        if (context == null) {
            return;
        }
        DegradeReason safeReason = reason == null ? DegradeReason.PERSIST_FAILED : reason;
        resolveMetrics(context).incrementDegradeTriggered(safeReason.code(), context.getMethodSignature());
        DegradeFallbackHandler.handleDegraded(context, entries, safeReason);
    }

    private BatchedMemoryAccountant resolveMemoryAccountant(LogCollectContext context) {
        GlobalBufferMemoryManager manager = context.getAttribute("__globalBufferManager", GlobalBufferMemoryManager.class);
        BatchedMemoryAccountant existing = context.getAttribute(ATTR_MEMORY_ACCOUNTANT, BatchedMemoryAccountant.class);
        if (existing != null && existing.globalManager() == manager) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        LogCollectConfig config = context.getConfig();
        long threshold = config == null ? 4096L : Math.max(1024L, config.getBufferMemorySyncThresholdBytes());
        BatchedMemoryAccountant created = new BatchedMemoryAccountant(manager, threshold);
        context.setAttribute(ATTR_MEMORY_ACCOUNTANT, created);
        return created;
    }

    private AggregateDirectBuffer resolveAggregateBuffer(LogCollectContext context, LogCollectConfig config) {
        AggregateDirectBuffer existing = context.getAttribute(ATTR_AGGREGATE_DIRECT_BUFFER, AggregateDirectBuffer.class);
        if (existing != null) {
            return existing;
        }
        int maxCount = config == null ? 100 : Math.max(1, config.getMaxBufferSize());
        long maxBytes = config == null ? 1024L * 1024L : Math.max(1024L, config.getMaxBufferBytes());
        AggregateDirectBuffer created = new AggregateDirectBuffer(maxCount, maxBytes);
        created.setPatternVersion(com.logcollect.api.format.LogLineDefaults.getVersion());
        context.setAttribute(ATTR_AGGREGATE_DIRECT_BUFFER, created);
        return created;
    }

    private String resolveLinePattern(LogCollectHandler handler) {
        if (handler == null) {
            return com.logcollect.api.format.LogLineDefaults.getEffectivePattern();
        }
        String pattern = handler.logLinePattern();
        if (pattern == null || pattern.isEmpty()) {
            return com.logcollect.api.format.LogLineDefaults.getEffectivePattern();
        }
        return pattern;
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

    private String messageSummary(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.length() <= 100 ? msg : msg.substring(0, 100);
    }

    private long estimateAggregateBytes(MutableProcessedLogRecord record) {
        long size = 64L;
        size += LogEntry.estimateStringBytes(record.getProcessedMessage());
        size += LogEntry.estimateStringBytes(record.getProcessedThrowable());
        return size;
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

    private AggregatedLog buildAggregatedLog(List<LogEntry> batch, boolean finalFlush) {
        if (batch == null || batch.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(batch.size() * 128);
        String maxLevel = "TRACE";
        long firstTs = Long.MAX_VALUE;
        long lastTs = 0L;
        for (LogEntry entry : batch) {
            if (entry == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getContent());
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
                java.util.UUID.randomUUID().toString(),
                sb.toString(),
                batch.size(),
                LogEntry.estimateStringBytes(sb.toString()),
                maxLevel,
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(firstTs), java.time.ZoneId.systemDefault()),
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(lastTs), java.time.ZoneId.systemDefault()),
                finalFlush);
    }

    private TransactionExecutor resolveTransactionExecutor(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isTransactionIsolation()) {
            return TransactionExecutor.DIRECT;
        }
        Object tx = context.getAttribute("__txWrapper");
        return tx instanceof TransactionExecutor ? (TransactionExecutor) tx : TransactionExecutor.DIRECT;
    }

    private LogCollectMetrics resolveMetrics(LogCollectContext context) {
        if (context == null) {
            return NoopLogCollectMetrics.INSTANCE;
        }
        LogCollectMetrics metrics = context.getAttribute("__metrics", LogCollectMetrics.class);
        return metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
    }

    private LogCollectMetrics resolveAnyMetrics() {
        for (LogCollectContext context : activeContexts.keySet()) {
            LogCollectMetrics metrics = resolveMetrics(context);
            if (!(metrics instanceof NoopLogCollectMetrics)) {
                return metrics;
            }
        }
        return NoopLogCollectMetrics.INSTANCE;
    }

    private LogCollectCircuitBreaker asBreaker(Object value) {
        return value instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) value : null;
    }

    private PipelineRingBuffer asRingBuffer(Object value) {
        return value instanceof PipelineRingBuffer ? (PipelineRingBuffer) value : null;
    }

    private SingleWriterBuffer asSingleWriterBuffer(Object value) {
        return value instanceof SingleWriterBuffer ? (SingleWriterBuffer) value : null;
    }

    private int resolveDrainBatch(LogCollectContext context) {
        LogCollectConfig config = context == null ? null : context.getConfig();
        if (config == null) {
            return DEFAULT_DRAIN_BATCH;
        }
        return Math.max(1, config.getPipelineConsumerDrainBatch());
    }

    private long resolveSyncRetryCapMs(LogCollectContext context) {
        LogCollectConfig config = context == null ? null : context.getConfig();
        if (config == null) {
            return 200L;
        }
        return Math.max(1L, config.getFlushRetrySyncCapMs());
    }

    private void refreshIdleThresholds(LogCollectContext context) {
        LogCollectConfig config = context == null ? null : context.getConfig();
        if (config == null) {
            return;
        }
        spinThreshold = Math.max(1, config.getPipelineConsumerSpinThreshold());
        yieldThreshold = Math.max(spinThreshold + 1, config.getPipelineConsumerYieldThreshold());
    }

    private boolean processClosingContexts() {
        for (LogCollectContext context : activeContexts.keySet()) {
            if (context == null || context.isClosed() || !context.isClosing()) {
                continue;
            }
            drainAndClose(context);
            return true;
        }
        return false;
    }

    private boolean isHighPriority(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
    }

    @SuppressWarnings("unused")
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

    private String resolveIdleStrategy() {
        return "ADAPTIVE";
    }

    private void idle(String strategy) {
        adaptiveIdleStrategy.idle(spinThreshold, yieldThreshold);
    }

    private void onSpinWaitCompat() {
        SpinWaitHint.onSpinWait();
    }

    @SuppressWarnings("unused")
    private void waitForFlushCompletion(SingleWriterBuffer buffer, long timeoutNanos) {
        if (buffer == null || timeoutNanos <= 0L) {
            return;
        }
        long deadline = System.nanoTime() + timeoutNanos;
        while (buffer.isFlushing() && System.nanoTime() < deadline) {
            onSpinWaitCompat();
        }
    }
}
