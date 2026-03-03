package com.logcollect.core.buffer;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SingleModeBufferBranchTest extends CoreUnitTestBase {

    @Test
    void constructorDumpAndForceFlush_coverBasicBranches() {
        SingleModeBuffer buffer = new SingleModeBuffer(10, parseBytes("1MB"), null);
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, null, null);

        buffer.offer(context, createTestEntry("s1", "INFO"));
        buffer.offer(context, createTestEntry("s2", "WARN"));

        assertThat(buffer.dumpAsString()).contains("s1").contains("s2");
        buffer.forceFlush();
        assertThat(buffer.dumpAsString()).isEmpty();
    }

    @Test
    void offerBranches_coverTooLargeGlobalRejectAndPolicies() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, null, null);

        SingleModeBuffer tooLarge = new SingleModeBuffer(100, 64, null);
        assertThat(tooLarge.offer(context, createTestEntry(repeat("x", 300), "INFO"))).isFalse();

        GlobalBufferMemoryManager memoryManager = new GlobalBufferMemoryManager(0);
        SingleModeBuffer globalRejected = new SingleModeBuffer(100, parseBytes("1MB"), memoryManager);
        assertThat(globalRejected.offer(context, createTestEntry("info", "INFO"))).isFalse();
        assertThat(globalRejected.offer(context, createTestEntry("warn", "WARN"))).isTrue();

        BoundedBufferPolicy dropNewest = new BoundedBufferPolicy(
                parseBytes("1MB"), 1, BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST);
        SingleModeBuffer newest = new SingleModeBuffer(1, parseBytes("1MB"), null, dropNewest);
        assertThat(newest.offer(context, createTestEntry("first", "INFO"))).isTrue();
        assertThat(newest.offer(context, createTestEntry("second", "INFO"))).isFalse();

        BoundedBufferPolicy dropOldest = new BoundedBufferPolicy(
                parseBytes("1MB"), 2, BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST);
        SingleModeBuffer oldest = new SingleModeBuffer(2, parseBytes("1MB"), null, dropOldest);
        oldest.offer(context, createTestEntry("o1", "INFO"));
        oldest.offer(context, createTestEntry("o2", "INFO"));
        oldest.offer(context, createTestEntry("o3", "WARN"));
        oldest.triggerFlush(context, true);
        verify(handler, never()).appendLog(eq(context), argThat(e -> "o1".equals(e.getContent())));
        verify(handler, atLeastOnce()).appendLog(eq(context), argThat(e -> "o3".equals(e.getContent())));
    }

    @Test
    void flushBranches_coverCircuitOpenWarnOnlyAndHandlerFailure() throws Exception {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);

        LogCollectCircuitBreaker breaker = new LogCollectCircuitBreaker(() -> config);
        breaker.forceOpen();
        LogCollectContext openContext = createTestContext(config, handler, CollectMode.SINGLE, null, breaker);
        SingleModeBuffer openBuffer = new SingleModeBuffer(10, parseBytes("1MB"), null);
        openBuffer.offer(openContext, createTestEntry("drop-me", "INFO"));
        openBuffer.triggerFlush(openContext, true);
        assertThat(openContext.getTotalDiscardedCount()).isGreaterThan(0);

        doThrow(new RuntimeException("append-fail"))
                .when(handler).appendLog(any(LogCollectContext.class), any(LogEntry.class));
        LogCollectContext failContext = createTestContext(config, handler, CollectMode.SINGLE, null, null);
        SingleModeBuffer failBuffer = new SingleModeBuffer(10, parseBytes("1MB"), null);
        failBuffer.offer(failContext, createTestEntry("info-drop", "INFO"));
        failBuffer.offer(failContext, createTestEntry("warn-keep", "WARN"));

        Method doTriggerFlush = SingleModeBuffer.class
                .getDeclaredMethod("doTriggerFlush", LogCollectContext.class, boolean.class, boolean.class);
        doTriggerFlush.setAccessible(true);
        doTriggerFlush.invoke(failBuffer, failContext, false, true);
        assertThat(failContext.getTotalDiscardedCount()).isGreaterThan(0);
    }

    @Test
    void privateHelpers_coverInterfaceInvocationAndTxExecution() throws Exception {
        LogCollectConfig config = baseConfig();
        config.setTransactionIsolation(true);
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, null, null);

        MetricsStub metrics = new MetricsStub();
        context.setAttribute("__metrics", metrics);
        TxWrapper txWrapper = new TxWrapper();
        context.setAttribute("__txWrapper", txWrapper);

        SingleModeBuffer buffer = new SingleModeBuffer(20, parseBytes("1MB"), null);

        Object resolvedMetrics = call(buffer, "resolveMetrics", new Class[]{LogCollectContext.class}, context);
        assertThat(resolvedMetrics).isSameAs(metrics);
        metrics.incrementDiscarded(context.getMethodSignature(), "unit_reason");
        assertThat(metrics.discardedCalls.get()).isEqualTo(1);

        TransactionExecutor txExecutor = (TransactionExecutor) call(
                buffer, "resolveTransactionExecutor", new Class[]{LogCollectContext.class}, context);
        AtomicInteger actionInvocations = new AtomicInteger(0);
        txExecutor.executeInNewTransaction(actionInvocations::incrementAndGet);
        assertThat(txWrapper.invocations.get()).isEqualTo(1);
        assertThat(actionInvocations.get()).isEqualTo(1);

        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, "FATAL")).isEqualTo(5);
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, "ERROR")).isEqualTo(4);
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, "WARN")).isEqualTo(3);
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, "INFO")).isEqualTo(2);
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, "DEBUG")).isEqualTo(1);
        assertThat((Integer) call(buffer, "levelRank", new Class[]{String.class}, (Object) null)).isZero();

        assertThat((Boolean) call(buffer, "isHighLevel", new Class[]{String.class}, "WARN")).isTrue();
        assertThat((Boolean) call(buffer, "isHighLevel", new Class[]{String.class}, "fatal")).isTrue();
        assertThat((Boolean) call(buffer, "isHighLevel", new Class[]{String.class}, "INFO")).isFalse();

        assertThat(call(buffer, "toDiscardReason",
                new Class[]{BoundedBufferPolicy.RejectReason.class},
                BoundedBufferPolicy.RejectReason.GLOBAL_MEMORY_LIMIT))
                .isEqualTo("global_memory_limit");
        assertThat(call(buffer, "toDiscardReason",
                new Class[]{BoundedBufferPolicy.RejectReason.class},
                BoundedBufferPolicy.RejectReason.BUFFER_FULL))
                .isEqualTo("buffer_full");

        @SuppressWarnings("unchecked")
        List<LogEntry> retained = (List<LogEntry>) call(buffer, "retainWarnOrAbove",
                new Class[]{List.class},
                Arrays.asList(
                        createTestEntry("a", "INFO"),
                        null,
                        createTestEntry("b", "WARN"),
                        createTestEntry("c", "ERROR")));
        assertThat(retained).hasSize(2);

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) call(buffer, "toLines", new Class[]{List.class},
                Arrays.asList(createTestEntry("x", "INFO"), createTestEntry("y", "ERROR")));
        assertThat(lines).containsExactly("x", "y");
        assertThat(call(buffer, "joinContents", new Class[]{List.class},
                Arrays.asList(createTestEntry("x", "INFO"), createTestEntry("y", "ERROR"))))
                .isEqualTo("x\ny");
        assertThat(call(buffer, "maxLevel", new Class[]{List.class},
                Arrays.asList(createTestEntry("x", "INFO"), createTestEntry("y", "ERROR"))))
                .isEqualTo("ERROR");
    }

    @Test
    void asyncBufferTask_coverRunDowngradeAndDiscard() throws Exception {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, null, null);
        SingleModeBuffer buffer = new SingleModeBuffer(10, parseBytes("1MB"), null);

        Class<?> taskClass = Class.forName("com.logcollect.core.buffer.SingleModeBuffer$AsyncBufferFlushTask");
        Constructor<?> ctor = taskClass.getDeclaredConstructor(
                SingleModeBuffer.class, LogCollectContext.class, boolean.class, boolean.class);
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
    void offer_flushEarlyCallback_executesLambdaPath() {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, null, null);
        SingleModeBuffer buffer = new SingleModeBuffer(1, parseBytes("1MB"), null);

        assertThat(buffer.offer(context, createTestEntry("first", "INFO"))).isTrue();
        assertThat(buffer.offer(context, createTestEntry("second", "INFO"))).isTrue();
        verify(handler, atLeastOnce()).appendLog(eq(context), any(LogEntry.class));
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
