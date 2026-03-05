package com.logcollect.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.logcollect.api.backpressure.BackpressureAction;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.enums.SamplingStrategy;
import com.logcollect.api.enums.TotalLimitPolicy;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.context.LogCollectIgnoreManager;
import com.logcollect.core.pipeline.PipelineQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;

class LogCollectLogbackAppenderBranchTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
        LogCollectIgnoreManager.clear();
    }

    @Test
    void privateHelpers_coverLevelAndStringBranches() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        assertThat(invoke(appender, "toLevelRank", new Class[]{String.class}, "ERROR")).isEqualTo(4);
        assertThat(invoke(appender, "toLevelRank", new Class[]{String.class}, "TRACE")).isEqualTo(0);
        assertThat(invoke(appender, "toLevelRank", new Class[]{String.class}, (Object) null)).isEqualTo(0);

        assertThat(invoke(appender, "isInternalLogger", new Class[]{String.class}, "com.logcollect.internal.X")).isEqualTo(true);
        assertThat(invoke(appender, "isInternalLogger", new Class[]{String.class}, "io.github.morana.logcollect.internal.X")).isEqualTo(true);
        assertThat(invoke(appender, "isInternalLogger", new Class[]{String.class}, "com.biz.X")).isEqualTo(false);

        assertThat(invoke(appender, "normalizeSamplingRate", new Class[]{double.class}, Double.NaN)).isEqualTo(0.0d);
        assertThat(invoke(appender, "normalizeSamplingRate", new Class[]{double.class}, -1.0d)).isEqualTo(0.0d);
        assertThat(invoke(appender, "normalizeSamplingRate", new Class[]{double.class}, 2.0d)).isEqualTo(1.0d);
        assertThat(invoke(appender, "normalizeSamplingRate", new Class[]{double.class}, 0.2d)).isEqualTo(0.2d);

        assertThat(invoke(appender, "isWarnOrAbove", new Class[]{String.class}, "WARN")).isEqualTo(true);
        assertThat(invoke(appender, "isWarnOrAbove", new Class[]{String.class}, "INFO")).isEqualTo(false);

        assertThat(invoke(appender, "estimateStringBytes", new Class[]{String.class}, (Object) null)).isEqualTo(0L);
        assertThat(invoke(appender, "estimateStringBytes", new Class[]{String.class}, "abcd")).isEqualTo(56L);
    }

    @Test
    void privateHelpers_coverTransactionExecutorBranches() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        TxWrapper tx = new TxWrapper();

        LogCollectConfig cfg = LogCollectConfig.frameworkDefaults();
        cfg.setTransactionIsolation(true);
        LogCollectContext ctx = newContext("trace-tx", cfg, mock(LogCollectHandler.class));
        ctx.setAttribute("__txWrapper", tx);

        TransactionExecutor resolved = (TransactionExecutor) invoke(
                appender,
                "resolveTransactionExecutor",
                new Class[]{LogCollectContext.class},
                ctx);
        resolved.executeInNewTransaction(() -> tx.directRun++);
        assertThat(tx.called).isEqualTo(1);
        assertThat(tx.directRun).isEqualTo(1);

        LogCollectConfig noTxConfig = LogCollectConfig.frameworkDefaults();
        noTxConfig.setTransactionIsolation(false);
        LogCollectContext noTxCtx = newContext("trace-no-tx", noTxConfig, mock(LogCollectHandler.class));
        TransactionExecutor direct = (TransactionExecutor) invoke(
                appender,
                "resolveTransactionExecutor",
                new Class[]{LogCollectContext.class},
                noTxCtx);
        assertThat(direct).isSameAs(TransactionExecutor.DIRECT);
    }

    @Test
    void privateHelpers_coverBackpressureAndSamplingBranches() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectConfig cfg = LogCollectConfig.frameworkDefaults();
        cfg.setSamplingRate(1.0d);
        cfg.setSamplingStrategy(SamplingStrategy.COUNT);
        LogCollectContext ctx = newContext("trace-bp", cfg, handler);

        assertThat(invoke(appender, "allowByBackpressure",
                new Class[]{LogCollectContext.class, String.class, String.class, LogCollectMetrics.class},
                ctx, "INFO", ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(true);

        BackpressureCallback throwingCallback = new BackpressureCallback() {
            @Override
            public BackpressureAction onPressure(double utilization) {
                throw new RuntimeException("boom");
            }
        };
        ctx.setAttribute("__backpressureCallback", throwingCallback);
        assertThat(invoke(appender, "allowByBackpressure",
                new Class[]{LogCollectContext.class, String.class, String.class, LogCollectMetrics.class},
                ctx, "INFO", ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(true);

        ctx.setAttribute("__backpressureCallback", new BackpressureCallback() {
            @Override
            public BackpressureAction onPressure(double utilization) {
                return BackpressureAction.SKIP_DEBUG_INFO;
            }
        });
        assertThat(invoke(appender, "allowByBackpressure",
                new Class[]{LogCollectContext.class, String.class, String.class, LogCollectMetrics.class},
                ctx, "INFO", ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(false);
        assertThat(invoke(appender, "allowByBackpressure",
                new Class[]{LogCollectContext.class, String.class, String.class, LogCollectMetrics.class},
                ctx, "ERROR", ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(true);

        ctx.setAttribute("__backpressureCallback", new BackpressureCallback() {
            @Override
            public BackpressureAction onPressure(double utilization) {
                return BackpressureAction.PAUSE;
            }
        });
        assertThat(invoke(appender, "allowByBackpressure",
                new Class[]{LogCollectContext.class, String.class, String.class, LogCollectMetrics.class},
                ctx, "WARN", ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(false);

        cfg.setSamplingRate(0.5d);
        cfg.setSamplingStrategy(SamplingStrategy.COUNT);
        assertThat(invoke(appender, "shouldSampleByCount",
                new Class[]{LogCollectContext.class, double.class}, ctx, 0.5d)).isEqualTo(false);
        assertThat(invoke(appender, "shouldSampleByCount",
                new Class[]{LogCollectContext.class, double.class}, ctx, 0.5d)).isEqualTo(true);
    }

    @Test
    void privateHelpers_coverTotalLimitPoliciesAndRethrow() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        LogCollectConfig cfg = LogCollectConfig.frameworkDefaults();
        cfg.setMaxTotalCollect(1);
        cfg.setTotalLimitPolicy(TotalLimitPolicy.STOP_COLLECTING);
        cfg.setSamplingRate(1.0d);
        LogCollectContext ctx = newContext("trace-limit", cfg, handler);
        ctx.incrementCollectedCount();

        LogEntry entry = LogEntry.builder()
                .traceId(ctx.getTraceId())
                .content("c")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("L")
                .build();
        assertThat(invoke(appender, "allowByTotalLimitAndSampling",
                new Class[]{LogCollectContext.class, String.class, long.class, String.class, LogCollectMetrics.class},
                ctx, entry.getLevel(), entry.estimateBytes(), ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(false);

        cfg.setTotalLimitPolicy(TotalLimitPolicy.DOWNGRADE_LEVEL);
        assertThat(invoke(appender, "allowByTotalLimitAndSampling",
                new Class[]{LogCollectContext.class, String.class, long.class, String.class, LogCollectMetrics.class},
                ctx, entry.getLevel(), entry.estimateBytes(), ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(false);

        LogEntry warn = LogEntry.builder()
                .traceId(ctx.getTraceId())
                .content("w")
                .level("WARN")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("L")
                .build();
        assertThat(invoke(appender, "allowByTotalLimitAndSampling",
                new Class[]{LogCollectContext.class, String.class, long.class, String.class, LogCollectMetrics.class},
                ctx, warn.getLevel(), warn.estimateBytes(), ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(true);

        cfg.setTotalLimitPolicy(TotalLimitPolicy.SAMPLE);
        cfg.setSamplingRate(0.0d);
        assertThat(invoke(appender, "allowByTotalLimitAndSampling",
                new Class[]{LogCollectContext.class, String.class, long.class, String.class, LogCollectMetrics.class},
                ctx, warn.getLevel(), warn.estimateBytes(), ctx.getMethodSignature(), NoopLogCollectMetrics.INSTANCE)).isEqualTo(false);

        cfg.setBlockWhenDegradeFail(true);
        assertThatThrownBy(() -> invoke(appender, "rethrowDegradeIfNecessary",
                new Class[]{LogCollectContext.class, Throwable.class},
                ctx, new LogCollectDegradeException("degrade"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void append_securityMetricsAndThrowablePath_covered() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);

        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setUseBuffer(false);
        config.setEnableMetrics(true);
        config.setAsync(false);
        LogCollectContext context = newContext("trace-sec", config, handler);
        LogCollectContextManager.push(context);

        MetricsStub metrics = new MetricsStub();
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.setMetrics(metrics);
        appender.start();

        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-sec");
        ILoggingEvent event = mockEvent(
                "phone=13812345678\nline2",
                "com.test.Service",
                Level.ERROR,
                mdc,
                new RuntimeException("boom<script>"));

        appender.doAppend(event);

        verify(handler, atLeastOnce()).appendLog(eq(context), any(LogEntry.class));
        assertThat(metrics.securityTimerStarted).isGreaterThan(0);
    }

    @Test
    void append_ignoreAndLoggerFilterBranches_covered() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setUseBuffer(false);
        config.setExcludeLoggerPrefixes(new String[]{"com.skip"});
        LogCollectContext context = newContext("trace-ignore", config, handler);
        LogCollectContextManager.push(context);

        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.start();
        Map<String, String> mdc = Collections.singletonMap(LogCollectContextManager.TRACE_ID_KEY, "trace-ignore");

        LogCollectIgnoreManager.enter();
        appender.doAppend(mockEvent("m1", "com.test.A", Level.INFO, mdc, null));
        LogCollectIgnoreManager.clear();

        appender.doAppend(mockEvent("m2", "com.skip.X", Level.INFO, mdc, null));
        appender.doAppend(mockEvent("m3", "com.test.B", Level.DEBUG, mdc, null));

        verify(handler, never()).appendLog(any(), any());
    }

    @Test
    void privateHelpers_coverPipelineBranches() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.setPipelineManager(null);
        LogCollectMetrics metrics = mock(LogCollectMetrics.class);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();

        Class<?>[] appendToPipelineTypes = new Class[]{
                ILoggingEvent.class,
                LogCollectContext.class,
                String.class,
                String.class,
                Map.class,
                LogCollectMetrics.class
        };

        ILoggingEvent infoEvent = mockEvent(
                "pipeline-info",
                "com.test.Pipeline",
                Level.INFO,
                Collections.<String, String>emptyMap(),
                null);
        ILoggingEvent warnEvent = mockEvent(
                "pipeline-warn",
                "com.test.Pipeline",
                Level.WARN,
                Collections.<String, String>emptyMap(),
                null);

        LogCollectContext closedCtx = newContext("trace-pipe-closed", config, mock(LogCollectHandler.class));
        closedCtx.setPipelineQueue(new PipelineQueue(2, 1.0d, 1.0d));
        closedCtx.markClosed();
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                infoEvent, closedCtx, "INFO", "com.test.Pipeline", Collections.emptyMap(), metrics);
        assertThat(closedCtx.getTotalDiscardedCount()).isEqualTo(1);
        verify(metrics).incrementDiscarded(closedCtx.getMethodSignature(), "buffer_closed_late_arrival");

        LogCollectContext nullQueueCtx = newContext("trace-pipe-null", config, mock(LogCollectHandler.class));
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                infoEvent, nullQueueCtx, "INFO", "com.test.Pipeline", Collections.emptyMap(), metrics);
        assertThat(nullQueueCtx.getTotalDiscardedCount()).isZero();

        LogCollectContext acceptedCtx = newContext("trace-pipe-accept", config, mock(LogCollectHandler.class));
        PipelineQueue acceptedQueue = new PipelineQueue(4, 0.75d, 1.0d);
        acceptedCtx.setPipelineQueue(acceptedQueue);
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("key", "value");
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                infoEvent, acceptedCtx, "INFO", "com.test.Pipeline", mdc, metrics);
        assertThat(acceptedQueue.size()).isEqualTo(1);

        LogCollectContext fullCtx = newContext("trace-pipe-full", config, mock(LogCollectHandler.class));
        PipelineQueue fullQueue = new PipelineQueue(1, 1.0d, 1.0d);
        fullCtx.setPipelineQueue(fullQueue);
        Map<String, String> nullMdc = null;
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                infoEvent, fullCtx, "INFO", "com.test.Pipeline", nullMdc, metrics);
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                warnEvent, fullCtx, "WARN", "com.test.Pipeline", Collections.emptyMap(), metrics);
        assertThat(fullCtx.getTotalDiscardedCount()).isEqualTo(1);
        verify(metrics).incrementDiscarded(fullCtx.getMethodSignature(), DegradeReason.PIPELINE_QUEUE_FULL.code());
        verify(metrics).incrementPipelineBackpressure(fullCtx.getMethodSignature(), "WARN");

        LogCollectContext backpressureCtx = newContext("trace-pipe-backpressure", config, mock(LogCollectHandler.class));
        PipelineQueue backpressureQueue = new PipelineQueue(4, 0.25d, 0.25d);
        backpressureCtx.setPipelineQueue(backpressureQueue);
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                warnEvent, backpressureCtx, "WARN", "com.test.Pipeline", Collections.emptyMap(), metrics);
        int sizeBeforeReject = backpressureQueue.size();
        invoke(appender, "appendToPipeline", appendToPipelineTypes,
                infoEvent, backpressureCtx, "INFO", "com.test.Pipeline", Collections.emptyMap(), metrics);
        assertThat(backpressureQueue.size()).isEqualTo(sizeBeforeReject);
        assertThat(backpressureCtx.getTotalDiscardedCount()).isEqualTo(1);
        verify(metrics).incrementDiscarded(backpressureCtx.getMethodSignature(), DegradeReason.PIPELINE_BACKPRESSURE.code());
        verify(metrics).incrementPipelineBackpressure(backpressureCtx.getMethodSignature(), "INFO");
        verify(metrics, atLeastOnce()).updatePipelineQueueUtilization(anyString(), anyDouble());
    }

    @Test
    void privateHelpers_coverAdaptiveSamplingAndRegistryBranches() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.setSecurityRegistry(null);

        LogCollectConfig disabled = LogCollectConfig.frameworkDefaults();
        disabled.setEnableSanitize(false);
        disabled.setEnableMask(false);
        assertThat(invoke(appender, "resolveSanitizer",
                new Class[]{LogCollectConfig.class}, disabled)).isNull();
        assertThat(invoke(appender, "resolveMasker",
                new Class[]{LogCollectConfig.class}, disabled)).isNull();

        LogCollectConfig enabled = LogCollectConfig.frameworkDefaults();
        assertThat(invoke(appender, "resolveSanitizer",
                new Class[]{LogCollectConfig.class}, enabled)).isNotNull();
        assertThat(invoke(appender, "resolveMasker",
                new Class[]{LogCollectConfig.class}, enabled)).isNotNull();

        LogCollectContext ctx = newContext("trace-adaptive", enabled, mock(LogCollectHandler.class));
        assertThat(invoke(appender, "shouldSampleAdaptive",
                new Class[]{LogCollectContext.class, String.class, double.class},
                ctx, "INFO", 0.5d)).isEqualTo(true);

        GlobalBufferMemoryManager mid = new GlobalBufferMemoryManager(1024L * 1024L);
        mid.forceAllocate(600L * 1024L);
        ctx.setAttribute("__globalBufferManager", mid);
        assertThat(invoke(appender, "shouldSampleAdaptive",
                new Class[]{LogCollectContext.class, String.class, double.class},
                ctx, "INFO", 1.0d)).isEqualTo(true);

        GlobalBufferMemoryManager high = new GlobalBufferMemoryManager(1024L * 1024L);
        high.forceAllocate(900L * 1024L);
        ctx.setAttribute("__globalBufferManager", high);
        assertThat(invoke(appender, "shouldSampleAdaptive",
                new Class[]{LogCollectContext.class, String.class, double.class},
                ctx, "INFO", 0.2d)).isEqualTo(false);
        assertThat(invoke(appender, "shouldSampleAdaptive",
                new Class[]{LogCollectContext.class, String.class, double.class},
                ctx, "WARN", 0.2d)).isEqualTo(true);

        assertThat(invoke(appender, "shouldSample",
                new Class[]{LogCollectContext.class, LogCollectConfig.class, String.class, boolean.class},
                ctx, null, "INFO", false)).isEqualTo(true);

        enabled.setSamplingRate(1.0d);
        enabled.setSamplingStrategy(SamplingStrategy.RATE);
        assertThat(invoke(appender, "shouldSample",
                new Class[]{LogCollectContext.class, LogCollectConfig.class, String.class, boolean.class},
                ctx, enabled, "INFO", false)).isEqualTo(true);

        enabled.setSamplingRate(0.0d);
        assertThat(invoke(appender, "shouldSample",
                new Class[]{LogCollectContext.class, LogCollectConfig.class, String.class, boolean.class},
                ctx, enabled, "INFO", false)).isEqualTo(false);

        enabled.setSamplingRate(1.0d);
        enabled.setSamplingStrategy(SamplingStrategy.ADAPTIVE);
        assertThat(invoke(appender, "shouldSample",
                new Class[]{LogCollectContext.class, LogCollectConfig.class, String.class, boolean.class},
                ctx, enabled, "WARN", true)).isEqualTo(true);
    }

    @Test
    void privateHelpers_coverNotifyBranches() throws Exception {
        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();

        LogCollectHandler okHandler = mock(LogCollectHandler.class);
        LogCollectContext okCtx = newContext("trace-notify-ok", config, okHandler);
        invoke(appender, "notifyError",
                new Class[]{LogCollectContext.class, Throwable.class, String.class},
                okCtx, new RuntimeException("x"), "append");
        verify(okHandler).onError(eq(okCtx), any(Throwable.class), eq("append"));

        invoke(appender, "notifyDegrade",
                new Class[]{LogCollectContext.class, String.class},
                okCtx, "reason-ok");
        verify(okHandler).onDegrade(eq(okCtx), any());

        LogCollectHandler failHandler = mock(LogCollectHandler.class);
        doThrow(new RuntimeException("onError-failed"))
                .when(failHandler).onError(any(LogCollectContext.class), any(Throwable.class), anyString());
        doThrow(new RuntimeException("onDegrade-failed"))
                .when(failHandler).onDegrade(any(LogCollectContext.class), any());
        LogCollectContext failCtx = newContext("trace-notify-fail", config, failHandler);
        invoke(appender, "notifyError",
                new Class[]{LogCollectContext.class, Throwable.class, String.class},
                failCtx, new RuntimeException("y"), "append");
        invoke(appender, "notifyDegrade",
                new Class[]{LogCollectContext.class, String.class},
                failCtx, "reason-fail");

        LogCollectContext noHandlerCtx = newContext("trace-no-handler", config, null);
        invoke(appender, "notifyError",
                new Class[]{LogCollectContext.class, Throwable.class, String.class},
                noHandlerCtx, new RuntimeException("z"), "append");

        LogCollectContext nullConfigCtx = newContext("trace-null-config", null, okHandler);
        invoke(appender, "notifyDegrade",
                new Class[]{LogCollectContext.class, String.class},
                nullConfigCtx, "reason-null-config");
    }

    private ILoggingEvent mockEvent(String msg, String logger, Level level, Map<String, String> mdc, Throwable t) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(mdc);
        when(event.getFormattedMessage()).thenReturn(msg);
        when(event.getLoggerName()).thenReturn(logger);
        when(event.getLevel()).thenReturn(level);
        when(event.getThreadName()).thenReturn("main");
        when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
        if (t != null) {
            when(event.getThrowableProxy()).thenReturn(new ThrowableProxy(t));
        }
        return event;
    }

    private LogCollectContext newContext(String traceId, LogCollectConfig config, LogCollectHandler handler) throws Exception {
        Method method = LogCollectLogbackAppenderBranchTest.class.getDeclaredMethod("marker");
        return new LogCollectContext(traceId, method, new Object[0], config, handler, null, null, CollectMode.SINGLE);
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    @SuppressWarnings("unused")
    private static void marker() {
    }

    private static final class TxWrapper implements TransactionExecutor {
        private int called;
        private int directRun;

        @Override
        public void executeInNewTransaction(Runnable action) {
            called++;
            action.run();
        }
    }

    @SuppressWarnings("unused")
    public static final class MetricsStub implements LogCollectMetrics {
        int securityTimerStarted;

        public Object startSecurityTimer() {
            securityTimerStarted++;
            return new Object();
        }

        public void stopSecurityTimer(Object timer, String method) {
            securityTimerStarted++;
        }

        public void incrementSanitizeHits(String method) {
            securityTimerStarted++;
        }

        public void incrementMaskHits(String method) {
            securityTimerStarted++;
        }

        public void incrementDiscarded(String method, String reason) {
            securityTimerStarted++;
        }

        public void incrementCollected(String method, String level, String mode) {
            securityTimerStarted++;
        }

        @Override
        public void incrementPersisted(String method, String mode) {
            securityTimerStarted++;
        }

        @Override
        public void incrementPersistFailed(String method) {
            securityTimerStarted++;
        }

        @Override
        public void incrementFlush(String methodKey, String mode, String trigger) {
            securityTimerStarted++;
        }

        @Override
        public void incrementBufferOverflow(String methodKey, String overflowPolicy) {
            securityTimerStarted++;
        }

        @Override
        public void incrementDegradeTriggered(String type, String methodKey) {
            securityTimerStarted++;
        }

        @Override
        public void incrementCircuitRecovered(String methodKey) {
            securityTimerStarted++;
        }

        @Override
        public void incrementHandlerTimeout(String methodKey) {
            securityTimerStarted++;
        }

        @Override
        public void updateBufferUtilization(String methodKey, double utilization) {
            securityTimerStarted++;
        }

        @Override
        public void updateGlobalBufferUtilization(double utilization) {
            securityTimerStarted++;
        }

        public Object startPersistTimer() {
            securityTimerStarted++;
            return new Object();
        }

        public void stopPersistTimer(Object timer, String method, String mode) {
            securityTimerStarted++;
        }
    }
}
