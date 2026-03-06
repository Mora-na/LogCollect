package com.logcollect.core.pipeline;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.SingleWriterBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.security.SecurityComponentRegistry;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.anyDouble;

class PipelineConsumerCoverageTest extends CoreUnitTestBase {

    @Test
    void flushBatchSync_coverAggregateSuccessCircuitOpenAndFailure() throws Exception {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.aggregatedLogSeparator()).thenReturn("|");
        when(handler.formatLogLine(any(LogEntry.class)))
                .thenThrow(new RuntimeException("format-fail"))
                .thenReturn("line-2");

        LogCollectCircuitBreaker breaker = new LogCollectCircuitBreaker(() -> config);
        SingleWriterBuffer buffer = new SingleWriterBuffer(8, parseBytes("2MB"), 8);
        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, buffer, breaker);
        context.setAttribute("__metrics", mock(LogCollectMetrics.class));
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));

        PipelineConsumer consumer = new PipelineConsumer("ut-aggregate", null);
        Method flushBatchSync = method("flushBatchSync",
                LogCollectContext.class, List.class, boolean.class, boolean.class);

        List<LogEntry> batch = Arrays.asList(
                createTestEntry("first", "INFO"),
                createTestEntry("second", "ERROR"));

        boolean success = (Boolean) flushBatchSync.invoke(consumer, context, batch, true, true);
        assertThat(success).isTrue();
        verify(handler, atLeastOnce()).flushAggregatedLog(eq(context), any(AggregatedLog.class));

        breaker.forceOpen();
        boolean blocked = (Boolean) flushBatchSync.invoke(consumer, context, batch, false, true);
        assertThat(blocked).isFalse();

        reset(handler);
        when(handler.aggregatedLogSeparator()).thenReturn(",");
        when(handler.formatLogLine(any(LogEntry.class))).thenReturn("line");
        doThrow(new RuntimeException("flush-fail"))
                .when(handler).flushAggregatedLog(eq(context), any(AggregatedLog.class));
        breaker.manualReset();
        boolean exhausted = (Boolean) flushBatchSync.invoke(consumer, context, batch, false, true);
        assertThat(exhausted).isFalse();
    }

    @Test
    void privateHelpers_coverRemainingBranches() throws Exception {
        PipelineConsumer consumer = new PipelineConsumer("ut-helper", null);
        assertThat(consumer.consumerName()).isEqualTo("ut-helper");
        consumer.assign(null);
        consumer.remove(null);

        LogCollectConfig config = baseConfig();
        LogCollectHandler throwingHandler = mock(LogCollectHandler.class);
        when(throwingHandler.shouldCollect(any(LogCollectContext.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        SingleWriterBuffer buffer = new SingleWriterBuffer(8, parseBytes("1MB"), 8);
        LogCollectContext throwingContext = createTestContext(config, throwingHandler, CollectMode.SINGLE, buffer, null);
        PipelineRingBuffer throwingQueue = new PipelineRingBuffer(4, 4);
        throwingContext.setPipelineQueue(throwingQueue);
        throwingContext.setAttribute("__metrics", mock(LogCollectMetrics.class));
        publishToRing(throwingContext, "boom", "INFO");

        Method processContextBatch = method("processContextBatch", LogCollectContext.class);
        assertThat((Integer) processContextBatch.invoke(consumer, new Object[]{null})).isZero();

        LogCollectContext closingContext = createTestContext(config, throwingHandler, CollectMode.SINGLE, buffer, null);
        closingContext.setPipelineQueue(new PipelineRingBuffer(2, 2));
        closingContext.markClosing();
        assertThat((Integer) processContextBatch.invoke(consumer, closingContext)).isZero();

        LogCollectContext noQueueContext = createTestContext(config, throwingHandler, CollectMode.SINGLE, buffer, null);
        assertThat((Integer) processContextBatch.invoke(consumer, noQueueContext)).isZero();

        processContextBatch.invoke(consumer, throwingContext);
        assertThat(throwingContext.getTotalDiscardedCount()).isGreaterThan(0);

        LogCollectContext handoffContext = createTestContext(config, mock(LogCollectHandler.class), CollectMode.SINGLE, buffer, null);
        handoffContext.setConsumerProcessing(true);
        Thread handoffRelease = new Thread(() -> {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            handoffContext.setConsumerProcessing(false);
        });
        handoffRelease.start();
        consumer.awaitHandoff(handoffContext, TimeUnit.MILLISECONDS.toNanos(50));
        handoffRelease.join(200L);

        LogCollectContext requeueContext = createTestContext(config, mock(LogCollectHandler.class), CollectMode.SINGLE, buffer, null);
        PipelineRingBuffer requeueQueue = new PipelineRingBuffer(2, 1);
        requeueContext.setPipelineQueue(requeueQueue);
        requeueContext.setAttribute("__metrics", mock(LogCollectMetrics.class));
        requeueQueue.offerOverflow(raw(requeueContext, "full", "WARN"));
        Method requeue = method("requeueForClosingHandoff", LogCollectContext.class, RawLogRecord.class);
        requeue.invoke(consumer, requeueContext, raw(requeueContext, "late-error", "ERROR"));
        assertThat(requeueContext.getTotalDiscardedCount()).isGreaterThan(0);

        Method processOneRecord = method("processOneRecord", LogCollectContext.class, RawLogRecord.class, boolean.class);
        LogCollectHandler collectHandler = mock(LogCollectHandler.class);
        when(collectHandler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectConfig byteLimitConfig = baseConfig();
        byteLimitConfig.setMaxTotalCollectBytes(1L);
        LogCollectContext byteLimitContext = createTestContext(
                byteLimitConfig, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        byteLimitContext.setAttribute("__metrics", mock(LogCollectMetrics.class));
        byteLimitContext.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));
        processOneRecord.invoke(consumer, byteLimitContext, raw(byteLimitContext, "limit-content", "INFO"), false);
        assertThat(byteLimitContext.getTotalDiscardedCount()).isGreaterThan(0);

        Method flushIfNeeded = method("flushIfNeeded", LogCollectContext.class, boolean.class);
        LogCollectContext nullBufferContext = createTestContext(config, collectHandler, CollectMode.SINGLE, null, null);
        flushIfNeeded.invoke(consumer, nullBufferContext, false);
        LogCollectContext emptyBufferContext = createTestContext(config, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(4, parseBytes("1MB"), 4), null);
        flushIfNeeded.invoke(consumer, emptyBufferContext, false);

        Method handleOversizedEntry = method("handleOversizedEntry", LogCollectContext.class, LogEntry.class, long.class);
        LogCollectHandler appendFailingHandler = mock(LogCollectHandler.class);
        doThrow(new RuntimeException("append-fail"))
                .when(appendFailingHandler).appendLog(any(LogCollectContext.class), any(LogEntry.class));
        LogCollectContext oversizedContext = createTestContext(config, appendFailingHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        oversizedContext.setAttribute("__metrics", mock(LogCollectMetrics.class));
        oversizedContext.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));
        LogEntry oversized = createTestEntry(repeat("z", 512), "ERROR");
        handleOversizedEntry.invoke(consumer, oversizedContext, oversized, oversized.estimateBytes());

        Method tryGlobalAllocate = method("tryGlobalAllocate", LogCollectContext.class, LogEntry.class, long.class);
        LogCollectContext noManagerContext = createTestContext(config, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        assertThat((Boolean) tryGlobalAllocate.invoke(consumer, noManagerContext, createTestEntry("a", "INFO"), 128L))
                .isTrue();

        GlobalBufferMemoryManager forceOk = new GlobalBufferMemoryManager(
                parseBytes("1MB"),
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                parseBytes("2MB"));
        assertThat(forceOk.tryAllocate(parseBytes("1MB"))).isTrue();
        LogCollectContext forceContext = createTestContext(config, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        forceContext.setAttribute("__metrics", mock(LogCollectMetrics.class));
        forceContext.setAttribute("__globalBufferManager", forceOk);
        assertThat((Boolean) tryGlobalAllocate.invoke(consumer, forceContext, createTestEntry("high", "ERROR"), 64L))
                .isTrue();

        GlobalBufferMemoryManager forceRejected = new GlobalBufferMemoryManager(
                parseBytes("1MB"),
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                parseBytes("1MB"));
        assertThat(forceRejected.tryAllocate(parseBytes("1MB"))).isTrue();
        LogCollectContext rejectContext = createTestContext(config, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        rejectContext.setAttribute("__metrics", mock(LogCollectMetrics.class));
        rejectContext.setAttribute("__globalBufferManager", forceRejected);
        assertThat((Boolean) tryGlobalAllocate.invoke(consumer, rejectContext, createTestEntry("high", "ERROR"), 64L))
                .isFalse();
        assertThat((Boolean) tryGlobalAllocate.invoke(consumer, rejectContext, createTestEntry("low", "INFO"), 64L))
                .isFalse();

        Method releaseGlobal = method("releaseGlobal", LogCollectContext.class, long.class);
        releaseGlobal.invoke(consumer, rejectContext, 0L);
        releaseGlobal.invoke(consumer, null, 128L);

        Method isTotalLimitReached = method("isTotalLimitReached", LogCollectContext.class, LogCollectConfig.class, long.class);
        assertThat((Boolean) isTotalLimitReached.invoke(consumer, null, config, 0L)).isFalse();
        assertThat((Boolean) isTotalLimitReached.invoke(consumer, rejectContext, null, 0L)).isFalse();

        Method resolveSecurityPipeline = method("resolveSecurityPipeline",
                LogCollectContext.class, LogCollectConfig.class, String.class, LogCollectMetrics.class);
        LogCollectConfig secureConfig = baseConfig();
        secureConfig.setEnableSanitize(true);
        secureConfig.setEnableMask(true);
        LogCollectContext secureContext = createTestContext(secureConfig, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        LogCollectMetrics metrics = mock(LogCollectMetrics.class);
        Object created = resolveSecurityPipeline.invoke(consumer, secureContext, secureConfig,
                secureContext.getMethodSignature(), metrics);
        assertThat(created).isNotNull();
        Object cached = resolveSecurityPipeline.invoke(consumer, secureContext, secureConfig,
                secureContext.getMethodSignature(), metrics);
        assertThat(cached).isSameAs(created);

        SecurityComponentRegistry registry = mock(SecurityComponentRegistry.class);
        when(registry.getSanitizer(any(LogCollectConfig.class))).thenReturn(new LogSanitizer() {
            @Override
            public String sanitize(String raw) {
                return raw;
            }

            @Override
            public String sanitizeThrowable(String throwableString) {
                return throwableString;
            }
        });
        when(registry.getMasker(any(LogCollectConfig.class))).thenReturn(new LogMasker() {
            @Override
            public String mask(String content) {
                return content;
            }
        });
        PipelineConsumer consumerWithRegistry = new PipelineConsumer("ut-registry", registry);
        LogCollectContext registryContext = createTestContext(secureConfig, collectHandler, CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8), null);
        Object fromRegistry = resolveSecurityPipeline.invoke(consumerWithRegistry, registryContext, secureConfig,
                registryContext.getMethodSignature(), metrics);
        assertThat(fromRegistry).isNotNull();

        Method resolveSecurityMetrics = method("resolveSecurityMetrics",
                LogCollectContext.class, String.class, LogCollectMetrics.class);
        Object securityMetrics = resolveSecurityMetrics.invoke(consumer, secureContext,
                secureContext.getMethodSignature(), metrics);
        assertThat(securityMetrics).isNotNull();

        Method routeToDegradation = method("routeToDegradation", LogCollectContext.class, List.class,
                com.logcollect.api.enums.DegradeReason.class);
        routeToDegradation.invoke(consumer, null, Collections.emptyList(), null);

        Method joinContents = method("joinContents", List.class);
        String joined = (String) joinContents.invoke(consumer,
                Arrays.asList(createTestEntry("line-a", "INFO"), null, createTestEntry("line-b", "WARN")));
        assertThat(joined).contains("line-a").contains("line-b");

        Method estimateBatchBytes = method("estimateBatchBytes", List.class);
        assertThat((Long) estimateBatchBytes.invoke(consumer, new Object[]{null})).isEqualTo(0L);

        Method resolveMetrics = method("resolveMetrics", LogCollectContext.class);
        assertThat(resolveMetrics.invoke(consumer, new Object[]{null})).isNotNull();

        Method resolveAnyMetrics = method("resolveAnyMetrics");
        assertThat(resolveAnyMetrics.invoke(consumer)).isNotNull();
        LogCollectContext metricContext = createTestContext(config, collectHandler, CollectMode.SINGLE, buffer, null);
        LogCollectMetrics expectedMetrics = mock(LogCollectMetrics.class);
        metricContext.setAttribute("__metrics", expectedMetrics);
        consumer.assign(metricContext);
        assertThat(resolveAnyMetrics.invoke(consumer)).isSameAs(expectedMetrics);
        consumer.remove(metricContext);

        Method resolveTransactionExecutor = method("resolveTransactionExecutor", LogCollectContext.class);
        LogCollectConfig txConfig = baseConfig();
        txConfig.setTransactionIsolation(true);
        LogCollectContext txContext = createTestContext(txConfig, collectHandler, CollectMode.SINGLE, buffer, null);
        assertThat(resolveTransactionExecutor.invoke(consumer, txContext)).isEqualTo(TransactionExecutor.DIRECT);
        txContext.setAttribute("__txWrapper", TransactionExecutor.DIRECT);
        assertThat(resolveTransactionExecutor.invoke(consumer, txContext)).isEqualTo(TransactionExecutor.DIRECT);

        Method isHighPriority = method("isHighPriority", String.class);
        assertThat((Boolean) isHighPriority.invoke(consumer, new Object[]{null})).isFalse();
        assertThat((Boolean) isHighPriority.invoke(consumer, "WARN")).isTrue();

        Method levelRank = method("levelRank", String.class);
        assertThat((Integer) levelRank.invoke(consumer, new Object[]{null})).isZero();
        assertThat((Integer) levelRank.invoke(consumer, "DEBUG")).isEqualTo(1);
        assertThat((Integer) levelRank.invoke(consumer, "UNKNOWN")).isZero();

        setAtomicLong(consumer, "idleLoops", 2L);
        setAtomicLong(consumer, "totalLoops", 4L);
        Method idleRatio = method("idleRatio");
        assertThat((Double) idleRatio.invoke(consumer)).isEqualTo(0.5d);

        Method waitForFlushCompletion = method("waitForFlushCompletion", SingleWriterBuffer.class, long.class);
        waitForFlushCompletion.invoke(consumer, buffer, 0L);
        SingleWriterBuffer flushingBuffer = new SingleWriterBuffer(2, parseBytes("1MB"), 2);
        flushingBuffer.setFlushing(true);
        Thread flusher = new Thread(() -> {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            flushingBuffer.setFlushing(false);
        });
        flusher.start();
        waitForFlushCompletion.invoke(consumer, flushingBuffer, TimeUnit.MILLISECONDS.toNanos(50));
        flusher.join(200L);

        Method onSpinWaitCompat = method("onSpinWaitCompat");
        onSpinWaitCompat.invoke(consumer);
    }

    @Test
    void run_loopHitsIdleRatioUpdateBranch() throws Exception {
        PipelineConsumer consumer = new PipelineConsumer("ut-run", null);
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContext context = createTestContext(baseConfig(), handler, CollectMode.SINGLE,
                new SingleWriterBuffer(2, parseBytes("1MB"), 2), null);
        LogCollectMetrics metrics = mock(LogCollectMetrics.class);
        context.setAttribute("__metrics", metrics);
        consumer.assign(context);

        setAtomicLong(consumer, "totalLoops", 1023L);

        Thread worker = new Thread(consumer, "ut-consumer-run");
        worker.start();
        Thread.sleep(15L);
        consumer.shutdown();
        worker.interrupt();
        worker.join(500L);

        verify(metrics, atLeastOnce()).updatePipelineConsumerIdleRatio(eq("ut-run"), anyDouble());
    }

    private LogCollectConfig baseConfig() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        config.setEnableSanitize(false);
        config.setEnableMask(false);
        config.setEnableDegrade(true);
        config.setUseBuffer(true);
        return config;
    }

    private RawLogRecord raw(LogCollectContext context, String content, String level) {
        return new RawLogRecord(
                content,
                null,
                level,
                "ut.logger",
                "main",
                System.currentTimeMillis(),
                Collections.emptyMap(),
                context);
    }

    private void publishToRing(LogCollectContext context, String content, String level) {
        PipelineRingBuffer ringBuffer = (PipelineRingBuffer) context.getPipelineQueue();
        long sequence = ringBuffer.tryClaim();
        if (sequence >= 0L) {
            MutableRawLogRecord slot = ringBuffer.getSlot(sequence);
            slot.populate(
                    content,
                    level,
                    "ut.logger",
                    "main",
                    System.currentTimeMillis(),
                    context.getTraceId(),
                    null,
                    Collections.emptyMap());
            ringBuffer.publish(sequence);
            return;
        }
        ringBuffer.offerOverflow(raw(context, content, level));
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        Method method = PipelineConsumer.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private void setAtomicLong(PipelineConsumer consumer, String fieldName, long value) throws Exception {
        Field field = PipelineConsumer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        AtomicLong atomicLong = (AtomicLong) field.get(consumer);
        atomicLong.set(value);
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
