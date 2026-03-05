package com.logcollect.core.buffer;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.pipeline.SecurityPipeline;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AggregateModeBufferBranchTest extends CoreUnitTestBase {

    @Test
    void constructorDumpAndForceFlush_coverBasicBranches() {
        AggregateModeBuffer buffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, null);
        LogCollectConfig config = baseConfig();
        LogCollectContext context = createTestContext(config, mock(LogCollectHandler.class), CollectMode.AGGREGATE, null, null);

        buffer.offer(context, createTestEntry("a1", "INFO"));
        buffer.offer(context, createTestEntry("a2", "WARN"));
        assertThat(buffer.dumpAsString()).contains("a1").contains("a2");

        buffer.forceFlush();
        assertThat(buffer.dumpAsString()).isEmpty();
    }

    @Test
    void offerBranches_coverFormatErrorEmptyResultAndGlobalReject() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler formatFailHandler = mock(LogCollectHandler.class);
        when(formatFailHandler.formatLogLine(any(LogEntry.class))).thenThrow(new RuntimeException("format-fail"));
        when(formatFailHandler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectContext formatFailContext = createTestContext(
                config, formatFailHandler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer formatFailBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, formatFailHandler);
        assertThat(formatFailBuffer.offer(formatFailContext, createTestEntry("fallback-content", "INFO"))).isTrue();
        verify(formatFailHandler, atLeastOnce()).onError(eq(formatFailContext), any(Throwable.class), eq("formatLogLine"));

        LogCollectHandler emptyHandler = mock(LogCollectHandler.class);
        when(emptyHandler.formatLogLine(any(LogEntry.class))).thenReturn("");
        when(emptyHandler.aggregatedLogSeparator()).thenReturn("\n");
        LogCollectContext emptyContext = createTestContext(config, emptyHandler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer emptyBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, emptyHandler);
        assertThat(emptyBuffer.offer(emptyContext, createTestEntry("discard-me", "INFO"))).isFalse();

        GlobalBufferMemoryManager memory = new GlobalBufferMemoryManager(parseBytes("1MB"));
        assertThat(memory.forceAllocate(parseBytes("1100KB"))).isTrue();
        LogCollectHandler normalHandler = mock(LogCollectHandler.class);
        when(normalHandler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(normalHandler.aggregatedLogSeparator()).thenReturn("\n");
        LogCollectContext normalContext = createTestContext(config, normalHandler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer memoryBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), memory, normalHandler);
        assertThat(memoryBuffer.offer(normalContext, createTestEntry("low", "INFO"))).isFalse();
        assertThat(memoryBuffer.offer(normalContext, createTestEntry("high", "ERROR"))).isTrue();
    }

    @Test
    void flushBranches_coverCircuitOpenAndHandlerFailure() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectCircuitBreaker openBreaker = new LogCollectCircuitBreaker(() -> config);
        openBreaker.forceOpen();
        LogCollectContext openContext = createTestContext(config, handler, CollectMode.AGGREGATE, null, openBreaker);
        AggregateModeBuffer openBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);
        openBuffer.offer(openContext, createTestEntry("open-drop", "INFO"));
        openBuffer.triggerFlush(openContext, true);
        assertThat(openContext.getTotalDiscardedCount()).isGreaterThan(0);

        doThrow(new RuntimeException("flush-fail"))
                .when(handler).flushAggregatedLog(any(LogCollectContext.class), any(AggregatedLog.class));
        LogCollectContext failContext = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer failBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);
        failBuffer.offer(failContext, createTestEntry("fail", "WARN"));
        failBuffer.triggerFlush(failContext, true);
        assertThat(failContext.getTotalDiscardedCount()).isGreaterThan(0);
    }

    @Test
    void privateHelpers_coverInterfaceInvocationTxAndRetention() throws Exception {
        LogCollectConfig config = baseConfig();
        config.setTransactionIsolation(true);
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        MetricsStub metrics = new MetricsStub();
        context.setAttribute("__metrics", metrics);
        TxWrapper txWrapper = new TxWrapper();
        context.setAttribute("__txWrapper", txWrapper);

        AggregateModeBuffer buffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);

        Object resolvedMetrics = call(buffer, "resolveMetrics", new Class[]{LogCollectContext.class}, context);
        assertThat(resolvedMetrics).isSameAs(metrics);
        metrics.incrementDiscarded(context.getMethodSignature(), "reason");
        assertThat(metrics.discardedCalls.get()).isEqualTo(1);

        TransactionExecutor txExecutor = (TransactionExecutor) call(
                buffer, "resolveTransactionExecutor", new Class[]{LogCollectContext.class}, context);
        AtomicInteger actionInvocations = new AtomicInteger(0);
        txExecutor.executeInNewTransaction(actionInvocations::incrementAndGet);
        assertThat(txWrapper.invocations.get()).isEqualTo(1);
        assertThat(actionInvocations.get()).isEqualTo(1);

        assertThat((Boolean) call(buffer, "isHighLevel", new Class[]{String.class}, "WARN")).isTrue();
        assertThat((Boolean) call(buffer, "isHighLevel", new Class[]{String.class}, "INFO")).isFalse();
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, "ERROR")).isEqualTo(4);

        Class<?> segClass = Class.forName("com.logcollect.core.buffer.AggregateModeBuffer$LogSegment");
        Constructor<?> segCtor = segClass.getDeclaredConstructor(String.class, String.class, long.class, long.class, int.class);
        segCtor.setAccessible(true);
        List<Object> segments = new ArrayList<Object>();
        segments.add(segCtor.newInstance("i", "INFO", System.currentTimeMillis(), 64L, 1));
        segments.add(segCtor.newInstance("w", "WARN", System.currentTimeMillis(), 64L, 1));
        segments.add(segCtor.newInstance("e", "ERROR", System.currentTimeMillis(), 64L, 1));
        @SuppressWarnings("unchecked")
        List<Object> retained = (List<Object>) call(buffer, "retainWarnOrAbove", new Class[]{List.class}, segments);
        assertThat(retained).hasSize(2);
    }

    @Test
    void asyncBufferTask_coverRunDowngradeAndDiscard() throws Exception {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");
        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer buffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);

        Class<?> taskClass = Class.forName("com.logcollect.core.buffer.AggregateModeBuffer$AsyncBufferFlushTask");
        Constructor<?> ctor = taskClass.getDeclaredConstructor(
                AggregateModeBuffer.class, LogCollectContext.class, boolean.class, boolean.class);
        ctor.setAccessible(true);
        Object task = ctor.newInstance(buffer, context, false, false);

        Method run = taskClass.getDeclaredMethod("run");
        Method downgrade = taskClass.getDeclaredMethod("downgradeForRetry");
        Method onDiscard = taskClass.getDeclaredMethod("onDiscard", String.class);
        run.setAccessible(true);
        downgrade.setAccessible(true);
        onDiscard.setAccessible(true);

        run.invoke(task);
        assertThat(downgrade.invoke(task)).isNotNull();
        int beforeDiscard = context.getTotalDiscardedCount();
        onDiscard.invoke(task, "async_queue_full");
        assertThat(context.getTotalDiscardedCount()).isEqualTo(beforeDiscard + 1);

        Object finalTask = ctor.newInstance(buffer, context, true, false);
        assertThat(downgrade.invoke(finalTask)).isNull();
        Object warnOnlyTask = ctor.newInstance(buffer, context, false, true);
        assertThat(downgrade.invoke(warnOnlyTask)).isNull();
    }

    @Test
    void transactionWrapper_usedDuringFlushAggregated() {
        LogCollectConfig config = baseConfig();
        config.setTransactionIsolation(true);
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        TxWrapper txWrapper = new TxWrapper();
        context.setAttribute("__txWrapper", txWrapper);

        AggregateModeBuffer buffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);
        buffer.offer(context, createTestEntry("tx", "WARN"));
        buffer.triggerFlush(context, true);

        ArgumentCaptor<AggregatedLog> captor = ArgumentCaptor.forClass(AggregatedLog.class);
        verify(handler, atLeastOnce()).flushAggregatedLog(eq(context), captor.capture());
        assertThat(txWrapper.invocations.get()).isGreaterThan(0);
    }

    @Test
    void offer_flushEarlyCallback_executesLambdaPath() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");
        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer buffer = new AggregateModeBuffer(1, parseBytes("1MB"), null, handler);

        assertThat(buffer.offer(context, createTestEntry("first", "INFO"))).isTrue();
        assertThat(buffer.offer(context, createTestEntry("second", "INFO"))).isTrue();
        verify(handler, atLeastOnce()).flushAggregatedLog(eq(context), any(AggregatedLog.class));
    }

    @Test
    void offerRawBranches_coverNullClosedHandlerNullAndFormatterFailures() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenReturn("formatted");
        when(handler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer buffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);
        assertThat(buffer.offerRaw(context, null)).isFalse();

        SecurityPipeline pipeline = new SecurityPipeline(null, null);
        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                context.getTraceId(), "raw", "INFO", System.currentTimeMillis(),
                "t", "c", null, null, SecurityPipeline.SecurityMetrics.NOOP);

        buffer.closeAndFlush(context);
        assertThat(buffer.offerRaw(context, record)).isFalse();

        AggregateModeBuffer noHandlerBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, null);
        assertThat(noHandlerBuffer.offerRaw(context, record)).isTrue();
    }

    @Test
    void offerRawBranches_coverFormatExceptionAndError() {
        LogCollectConfig config = baseConfig();
        SecurityPipeline pipeline = new SecurityPipeline(null, null);

        LogCollectHandler runtimeHandler = mock(LogCollectHandler.class);
        when(runtimeHandler.aggregatedLogSeparator()).thenReturn("\n");
        when(runtimeHandler.formatLogLine(any(LogEntry.class))).thenThrow(new RuntimeException("format-fail"));
        LogCollectContext runtimeContext = createTestContext(config, runtimeHandler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer runtimeBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, runtimeHandler);
        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                runtimeContext.getTraceId(), "raw-runtime", "WARN", System.currentTimeMillis(),
                "t", "c", null, null, SecurityPipeline.SecurityMetrics.NOOP);
        assertThat(runtimeBuffer.offerRaw(runtimeContext, record)).isTrue();
        verify(runtimeHandler, atLeastOnce()).onError(eq(runtimeContext), any(Throwable.class), eq("formatLogLine"));
        verify(runtimeHandler, atLeastOnce()).formatLogLine(any(LogEntry.class));
        verify(runtimeHandler, never()).logLinePattern();

        LogCollectHandler errorHandler = mock(LogCollectHandler.class);
        when(errorHandler.aggregatedLogSeparator()).thenReturn("\n");
        when(errorHandler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> {
            throw new AssertionError("fatal-format");
        });
        LogCollectContext errorContext = createTestContext(config, errorHandler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer errorBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, errorHandler);
        SecurityPipeline.ProcessedLogRecord errorRecord = pipeline.processRawRecord(
                errorContext.getTraceId(), "raw-error", "ERROR", System.currentTimeMillis(),
                "t", "c", null, null, SecurityPipeline.SecurityMetrics.NOOP);
        assertThatThrownBy(() -> errorBuffer.offerRaw(errorContext, errorRecord))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void offerBranches_coverOversizedDirectFlushAndHardCeilingPaths() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");
        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);

        GlobalBufferMemoryManager exhausted = new GlobalBufferMemoryManager(
                parseBytes("1MB"),
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                parseBytes("1MB"));
        assertThat(exhausted.forceAllocate(parseBytes("1MB"))).isTrue();

        AggregateModeBuffer exhaustedBuffer = new AggregateModeBuffer(10, 64, exhausted, handler);
        assertThat(exhaustedBuffer.offer(context, createTestEntry(repeat("q", 512), "WARN"))).isFalse();

        GlobalBufferMemoryManager elastic = new GlobalBufferMemoryManager(
                parseBytes("1MB"),
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                parseBytes("2MB"));
        assertThat(elastic.forceAllocate(parseBytes("1MB"))).isTrue();
        AggregateModeBuffer directFlushBuffer = new AggregateModeBuffer(10, 64, elastic, handler);
        assertThat(directFlushBuffer.offer(context, createTestEntry(repeat("e", 512), "ERROR"))).isTrue();
        assertThat(elastic.getTotalUsed()).isEqualTo(parseBytes("1MB"));

        AggregateModeBuffer normalPath = new AggregateModeBuffer(10, parseBytes("1MB"), exhausted, handler);
        assertThat(normalPath.offer(context, createTestEntry("small-warn", "WARN"))).isFalse();
    }

    @Test
    void offerBranches_coverCircuitOpenCustomRejectAndReentrantFlush() throws Exception {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectConfig asyncConfig = baseConfig();
        asyncConfig.setAsync(true);
        LogCollectContext asyncContext = createTestContext(asyncConfig, handler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer asyncBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), null, handler);
        asyncBuffer.offer(asyncContext, createTestEntry("async-agg", "WARN"));
        asyncBuffer.triggerFlush(asyncContext, false);
        asyncBuffer.triggerFlush(asyncContext, true);

        LogCollectCircuitBreaker breaker = new LogCollectCircuitBreaker(() -> config);
        breaker.forceOpen();
        LogCollectContext openContext = createTestContext(config, handler, CollectMode.AGGREGATE, null, breaker);
        AggregateModeBuffer openBuffer = new AggregateModeBuffer(10, 64, null, handler);
        assertThat(openBuffer.offer(openContext, createTestEntry(repeat("b", 256), "INFO"))).isFalse();

        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(parseBytes("2MB"));
        BoundedBufferPolicy rejectPolicy = new BoundedBufferPolicy(
                parseBytes("1MB"),
                100,
                BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY) {
            @Override
            public RejectReason beforeAdd(long entryBytes, Runnable earlyFlush) {
                return RejectReason.BUFFER_FULL;
            }
        };
        AggregateModeBuffer rejectBuffer = new AggregateModeBuffer(10, parseBytes("1MB"), manager, handler, rejectPolicy);
        long before = manager.getTotalUsed();
        assertThat(rejectBuffer.offer(openContext, createTestEntry("reject", "INFO"))).isFalse();
        assertThat(manager.getTotalUsed()).isEqualTo(before);

        Field flushingField = AggregateModeBuffer.class.getDeclaredField("flushing");
        flushingField.setAccessible(true);
        AtomicBoolean flushing = (AtomicBoolean) flushingField.get(rejectBuffer);
        flushing.set(true);
        Method doTriggerFlush = AggregateModeBuffer.class
                .getDeclaredMethod("doTriggerFlush", LogCollectContext.class, boolean.class, boolean.class);
        doTriggerFlush.setAccessible(true);
        doTriggerFlush.invoke(rejectBuffer, openContext, false, false);
        flushing.set(false);
    }

    @Test
    void helperBranches_coverNullCollectionsLargeBuilderAndNotifyCallbacks() throws Exception {
        LogCollectConfig config = baseConfig();
        config.setEnableDegrade(false);
        LogCollectHandler handler = mock(LogCollectHandler.class);
        doThrow(new RuntimeException("degrade-callback"))
                .when(handler).onDegrade(any(LogCollectContext.class), any());
        doThrow(new RuntimeException("error-callback"))
                .when(handler).onError(any(LogCollectContext.class), any(Throwable.class), any(String.class));
        doThrow(new RuntimeException("flush-fail"))
                .when(handler).flushAggregatedLog(any(LogCollectContext.class), any(AggregatedLog.class));
        when(handler.formatLogLine(any(LogEntry.class))).thenAnswer(inv -> ((LogEntry) inv.getArgument(0)).getContent());
        when(handler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        AggregateModeBuffer buffer = new AggregateModeBuffer(10, 64, null, handler);
        assertThat(buffer.offer(context, createTestEntry(repeat("z", 512), "INFO"))).isFalse();
        assertThat(context.getTotalDiscardedCount()).isGreaterThan(0);

        assertThat(call(buffer, "splitByPatternVersion", new Class[]{List.class}, new Object[]{null}))
                .isEqualTo(Collections.emptyList());
        assertThat(call(buffer, "retainWarnOrAbove", new Class[]{List.class}, new Object[]{null})).isNull();
        assertThat(call(buffer, "buildAggregatedLog", new Class[]{List.class, boolean.class},
                Collections.emptyList(), false)).isNull();
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, new Object[]{null})).isZero();
        assertThat((Boolean) call(buffer, "isHighLevel", new Class[]{String.class}, new Object[]{null})).isFalse();

        @SuppressWarnings("unchecked")
        ThreadLocal<StringBuilder> holder = (ThreadLocal<StringBuilder>) getStaticField(
                AggregateModeBuffer.class, "SB_HOLDER");
        holder.get().ensureCapacity(2 * 1024 * 1024);
        Object segment = newSegment("large-builder", "WARN", System.currentTimeMillis(), 1);
        List<Object> segments = new ArrayList<Object>();
        segments.add(segment);
        assertThat(call(buffer, "buildAggregatedLog", new Class[]{List.class, boolean.class}, segments, false))
                .isNotNull();

        call(buffer, "notifyDegrade", new Class[]{LogCollectContext.class, String.class}, context, "reason");
        call(buffer, "notifyDegrade", new Class[]{LogCollectContext.class, String.class}, null, "reason");
        call(buffer, "notifyError",
                new Class[]{LogCollectContext.class, Throwable.class, String.class},
                context, new RuntimeException("x"), "phase");
        call(buffer, "notifyError",
                new Class[]{LogCollectContext.class, Throwable.class, String.class},
                null, new RuntimeException("x"), "phase");
    }

    private LogCollectConfig baseConfig() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        config.setEnableMetrics(true);
        config.setEnableDegrade(true);
        config.setDegradeStorage(DegradeStorage.DISCARD_ALL);
        return config;
    }

    private Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private Object getStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private Object newSegment(String formattedLine, String level, long timestamp, int version) throws Exception {
        Class<?> segClass = Class.forName("com.logcollect.core.buffer.AggregateModeBuffer$LogSegment");
        Constructor<?> ctor = segClass.getDeclaredConstructor(String.class, String.class, long.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(formattedLine, level, timestamp, version);
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    public static final class MetricsStub implements LogCollectMetrics {
        private final AtomicInteger discardedCalls = new AtomicInteger(0);

        @Override
        public void incrementDiscarded(String method, String reason) {
            discardedCalls.incrementAndGet();
        }

        @Override
        public void incrementCollected(String methodKey, String level, String mode) {
        }

        @Override
        public void incrementPersisted(String methodKey, String mode) {
        }

        @Override
        public void incrementPersistFailed(String methodKey) {
        }

        @Override
        public void incrementFlush(String methodKey, String mode, String trigger) {
        }

        @Override
        public void incrementBufferOverflow(String methodKey, String overflowPolicy) {
        }

        @Override
        public void incrementDegradeTriggered(String type, String methodKey) {
        }

        @Override
        public void incrementCircuitRecovered(String methodKey) {
        }

        @Override
        public void incrementSanitizeHits(String methodKey) {
        }

        @Override
        public void incrementMaskHits(String methodKey) {
        }

        @Override
        public void incrementHandlerTimeout(String methodKey) {
        }

        @Override
        public void updateBufferUtilization(String method, double utilization) {
        }

        @Override
        public void updateGlobalBufferUtilization(double utilization) {
        }

        @Override
        public Object startSecurityTimer() {
            return null;
        }

        @Override
        public void stopSecurityTimer(Object timerSample, String methodKey) {
        }

        @Override
        public Object startPersistTimer() {
            return null;
        }

        @Override
        public void stopPersistTimer(Object timerSample, String methodKey, String mode) {
        }
    }

    @SuppressWarnings("unused")
    public static final class TxWrapper implements TransactionExecutor {
        private final AtomicInteger invocations = new AtomicInteger(0);

        @Override
        public void executeInNewTransaction(Runnable action) {
            invocations.incrementAndGet();
            action.run();
        }
    }
}
