package com.logcollect.log4j2;

import com.logcollect.api.backpressure.BackpressureAction;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.buffer.AggregateModeBuffer;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.pipeline.LogCollectPipelineManager;
import com.logcollect.core.pipeline.PipelineRingBuffer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LogCollectLog4j2AppenderCoverageGapTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void mutatorsAndMdcExtraction_shouldCoverRequiredKeyBranches() throws Exception {
        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("x", null);
        appender.setMetrics(null);
        appender.setPipelineManager(new LogCollectPipelineManager(1, null));
        appender.setRequiredMdcKeys(null);

        assertThat(invoke(
                appender,
                "extractRelevantMdc",
                new Class[]{ReadOnlyStringMap.class, LogCollectContext.class},
                null,
                null)).isNull();

        ReadOnlyStringMap contextData = mock(ReadOnlyStringMap.class);
        when(contextData.getValue(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("tenant".equals(key)) {
                return "t1";
            }
            if ("userId".equals(key)) {
                return "u1";
            }
            if ("biz".equals(key)) {
                return "b1";
            }
            return null;
        });

        appender.setRequiredMdcKeys(new String[]{" tenant ", null, "", LogCollectContextManager.TRACE_ID_KEY, "userId"});
        @SuppressWarnings("unchecked")
        Map<String, String> staticKeys = (Map<String, String>) invoke(
                appender,
                "extractRelevantMdc",
                new Class[]{ReadOnlyStringMap.class, LogCollectContext.class},
                contextData,
                null);
        assertThat(staticKeys)
                .containsEntry("tenant", "t1")
                .containsEntry("userId", "u1");

        appender.setRequiredMdcKeys(new String[]{" ", LogCollectContextManager.TRACE_ID_KEY});
        LogCollectContext context = newContext("trace-mdc", baseConfig(), mock(LogCollectHandler.class), null, null, CollectMode.SINGLE);
        assertThat(invoke(
                appender,
                "extractRelevantMdc",
                new Class[]{ReadOnlyStringMap.class, LogCollectContext.class},
                contextData,
                context)).isNull();
    }

    @Test
    void append_pipelinePath_shouldCoverSuccessAndBackpressureBranches() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);

        LogCollectConfig config = baseConfig();
        config.setPipelineEnabled(true);
        PipelineRingBuffer ringBuffer = new PipelineRingBuffer(2, 1);
        LogCollectContext context = newContext("trace-pipe", config, handler, null, null, CollectMode.SINGLE);
        context.setPipelineQueue(ringBuffer);
        LogCollectContextManager.push(context);

        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("x", null);
        appender.setPipelineManager(new LogCollectPipelineManager(1, null));
        appender.setMetrics(mock(LogCollectMetrics.class));
        appender.start();

        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-pipe");

        appender.append(mockEvent("first", "first", null, "com.biz.Logger", Level.INFO, mdc, null));
        assertThat(ringBuffer.tryConsume()).isNotNull();

        assertThat(ringBuffer.tryClaim()).isEqualTo(1L);
        appender.append(mockEvent("second", "second", null, "com.biz.Logger", Level.INFO, mdc, null));
        appender.append(mockEvent("third", "third", null, "com.biz.Logger", Level.ERROR, mdc, null));
        appender.append(mockEvent("fourth", "fourth", null, "com.biz.Logger", Level.ERROR, mdc, null));

        assertThat(context.getTotalDiscardedCount()).isGreaterThan(0);
        assertThat(ringBuffer.pollOverflow()).isNotNull();
    }

    @Test
    void append_pipelineClosedQueueNullAndNonRing_shouldCoverEarlyReturns() throws Exception {
        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("x", null);
        appender.setPipelineManager(new LogCollectPipelineManager(1, null));
        LogCollectMetrics metrics = mock(LogCollectMetrics.class);

        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-closed");
        LogEvent event = mockEvent("m", "m", null, "com.biz.Logger", Level.INFO, mdc, null);

        LogCollectConfig config = baseConfig();
        config.setPipelineEnabled(true);

        LogCollectContext closingCtx = newContext("trace-closed", config, handler, null, null, CollectMode.SINGLE);
        closingCtx.setPipelineQueue(new PipelineRingBuffer(2, 1));
        closingCtx.markClosing();
        invoke(appender, "appendToPipeline",
                new Class[]{LogEvent.class, LogCollectContext.class, String.class, String.class, ReadOnlyStringMap.class, LogCollectMetrics.class},
                event, closingCtx, "INFO", "com.biz.Logger", event.getContextData(), metrics);
        assertThat(closingCtx.getTotalDiscardedCount()).isGreaterThan(0);

        LogCollectContext nullQueueCtx = newContext("trace-null-queue", config, handler, null, null, CollectMode.SINGLE);
        invoke(appender, "appendToPipeline",
                new Class[]{LogEvent.class, LogCollectContext.class, String.class, String.class, ReadOnlyStringMap.class, LogCollectMetrics.class},
                event, nullQueueCtx, "INFO", "com.biz.Logger", event.getContextData(), metrics);

        LogCollectContext nonRingCtx = newContext("trace-non-ring", config, handler, null, null, CollectMode.SINGLE);
        nonRingCtx.setPipelineQueue(new Object());
        invoke(appender, "appendToPipeline",
                new Class[]{LogEvent.class, LogCollectContext.class, String.class, String.class, ReadOnlyStringMap.class, LogCollectMetrics.class},
                event, nonRingCtx, "INFO", "com.biz.Logger", event.getContextData(), metrics);
        assertThat(nonRingCtx.getTotalDiscardedCount()).isGreaterThan(0);
    }

    @Test
    void append_bufferPaths_shouldCoverAggregateAndSingleBranches() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);

        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("x", null);
        appender.setMetrics(mock(LogCollectMetrics.class));
        appender.start();

        LogCollectConfig config = baseConfig();
        config.setUseBuffer(true);

        LogCollectBuffer singleBuffer = mock(LogCollectBuffer.class);
        when(singleBuffer.offer(any(LogCollectContext.class), any(LogEntry.class))).thenReturn(true);
        LogCollectContext singleCtx = newContext("trace-single-buffer", config, handler, singleBuffer, null, CollectMode.SINGLE);
        LogCollectContextManager.push(singleCtx);
        Map<String, String> singleMdc = new HashMap<String, String>();
        singleMdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-single-buffer");
        appender.append(mockEvent("single", "single", null, "com.biz.Buffer", Level.INFO, singleMdc, null));
        verify(singleBuffer).offer(any(LogCollectContext.class), any(LogEntry.class));
        LogCollectContextManager.pop();

        AggregateModeBuffer aggregateBuffer = mock(AggregateModeBuffer.class);
        when(aggregateBuffer.offerRaw(any(LogCollectContext.class), any())).thenReturn(true);
        LogCollectContext aggregateCtx = newContext("trace-agg-buffer", config, handler, aggregateBuffer, null, CollectMode.AGGREGATE);
        LogCollectContextManager.push(aggregateCtx);
        Map<String, String> aggMdc = new HashMap<String, String>();
        aggMdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-agg-buffer");
        appender.append(mockEvent("agg", "agg", null, "com.biz.Buffer", Level.INFO, aggMdc, null));
        verify(aggregateBuffer).offerRaw(any(LogCollectContext.class), any());
    }

    @Test
    void append_shouldCoverContextMissingFilterAndCatchBranches() throws Exception {
        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("x", null);
        appender.start();

        Map<String, String> unknownMdc = new HashMap<String, String>();
        unknownMdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-unknown");
        appender.append(mockEvent("x", "x", null, "com.biz.Logger", Level.INFO, unknownMdc, null));

        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(false);
        LogCollectContext context = newContext("trace-filter", baseConfig(), handler, null, null, CollectMode.SINGLE);
        LogCollectContextManager.push(context);
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-filter");
        appender.append(mockEvent("m", "m", null, "com.biz.Logger", Level.INFO, mdc, null));
        assertThat(context.getTotalDiscardedCount()).isGreaterThan(0);
        LogCollectContextManager.pop();

        LogCollectContext pausedCtx = newContext("trace-paused", baseConfig(), mock(LogCollectHandler.class), null, null, CollectMode.SINGLE);
        pausedCtx.setAttribute("__backpressureCallback", new BackpressureCallback() {
            @Override
            public BackpressureAction onPressure(double utilization) {
                return BackpressureAction.PAUSE;
            }
        });
        LogCollectContextManager.push(pausedCtx);
        Map<String, String> pausedMdc = new HashMap<String, String>();
        pausedMdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-paused");
        appender.append(mockEvent("p", "p", null, "com.biz.Logger", Level.INFO, pausedMdc, null));
        LogCollectContextManager.pop();

        LogEvent runtimeEvent = mock(LogEvent.class);
        when(runtimeEvent.getContextData()).thenThrow(new RuntimeException("boom"));
        assertThatCode(() -> appender.append(runtimeEvent)).doesNotThrowAnyException();

        LogEvent errorEvent = mock(LogEvent.class);
        when(errorEvent.getContextData()).thenThrow(new AssertionError("fatal"));
        assertThatThrownBy(() -> appender.append(errorEvent)).isInstanceOf(AssertionError.class);
    }

    @Test
    void privateHelpersAndDirectPath_shouldCoverResolveAndPersistenceBranches() throws Exception {
        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("x", null);
        LogCollectMetrics metrics = mock(LogCollectMetrics.class);
        LogEntry entry = entry("payload");

        assertThat(invoke(appender, "resolveRawMessage", new Class[]{LogEvent.class}, (Object) null)).isEqualTo("");

        LogEvent nullMsgEvent = mock(LogEvent.class);
        when(nullMsgEvent.getMessage()).thenReturn(null);
        assertThat(invoke(appender, "resolveRawMessage", new Class[]{LogEvent.class}, nullMsgEvent)).isEqualTo("");

        LogEvent templateEvent = mock(LogEvent.class);
        Message templateMessage = mock(Message.class);
        when(templateEvent.getMessage()).thenReturn(templateMessage);
        when(templateMessage.getFormat()).thenReturn("tpl");
        when(templateMessage.getParameters()).thenReturn(new Object[0]);
        assertThat(invoke(appender, "resolveRawMessage", new Class[]{LogEvent.class}, templateEvent)).isEqualTo("tpl");

        LogEvent fallbackEvent = mock(LogEvent.class);
        Message fallbackMessage = mock(Message.class);
        when(fallbackEvent.getMessage()).thenReturn(fallbackMessage);
        when(fallbackMessage.getFormat()).thenReturn(null);
        when(fallbackMessage.getParameters()).thenReturn(new Object[]{"x"});
        when(fallbackMessage.getFormattedMessage()).thenReturn(null);
        assertThat(invoke(appender, "resolveRawMessage", new Class[]{LogEvent.class}, fallbackEvent)).isEqualTo("");

        LogCollectContext noHandlerCtx = newContext("trace-no-handler", baseConfig(), null, null, null, CollectMode.SINGLE);
        invoke(appender, "doHandleDirect",
                new Class[]{LogCollectContext.class, LogEntry.class, String.class, LogCollectMetrics.class},
                noHandlerCtx, entry, noHandlerCtx.getMethodSignature(), metrics);

        LogCollectCircuitBreaker openBreaker = mock(LogCollectCircuitBreaker.class);
        when(openBreaker.allowWrite()).thenReturn(false);
        LogCollectContext openCtx = newContext("trace-open", baseConfig(), mock(LogCollectHandler.class), null, openBreaker, CollectMode.SINGLE);
        invoke(appender, "doHandleDirect",
                new Class[]{LogCollectContext.class, LogEntry.class, String.class, LogCollectMetrics.class},
                openCtx, entry, openCtx.getMethodSignature(), metrics);

        LogCollectHandler aggregateHandler = mock(LogCollectHandler.class);
        when(aggregateHandler.formatLogLine(any(LogEntry.class))).thenThrow(new RuntimeException("format"));
        LogCollectContext aggregateCtx = newContext("trace-agg", baseConfig(), aggregateHandler, null, null, CollectMode.AGGREGATE);
        invoke(appender, "doHandleDirect",
                new Class[]{LogCollectContext.class, LogEntry.class, String.class, LogCollectMetrics.class},
                aggregateCtx, entry, aggregateCtx.getMethodSignature(), metrics);
        verify(aggregateHandler).flushAggregatedLog(eq(aggregateCtx), any());

        LogCollectCircuitBreaker failBreaker = mock(LogCollectCircuitBreaker.class);
        when(failBreaker.allowWrite()).thenReturn(true);
        LogCollectHandler failingHandler = mock(LogCollectHandler.class);
        doThrow(new RuntimeException("append-fail"))
                .when(failingHandler).appendLog(any(LogCollectContext.class), any(LogEntry.class));
        LogCollectContext failCtx = newContext("trace-fail", baseConfig(), failingHandler, null, failBreaker, CollectMode.SINGLE);
        invoke(appender, "doHandleDirect",
                new Class[]{LogCollectContext.class, LogEntry.class, String.class, LogCollectMetrics.class},
                failCtx, entry, failCtx.getMethodSignature(), metrics);
        verify(failBreaker).recordFailure();

        LogCollectCircuitBreaker okBreaker = mock(LogCollectCircuitBreaker.class);
        when(okBreaker.allowWrite()).thenReturn(true);
        LogCollectHandler okHandler = mock(LogCollectHandler.class);
        LogCollectContext okCtx = newContext("trace-ok", baseConfig(), okHandler, null, okBreaker, CollectMode.SINGLE);
        invoke(appender, "doHandleDirect",
                new Class[]{LogCollectContext.class, LogEntry.class, String.class, LogCollectMetrics.class},
                okCtx, entry, okCtx.getMethodSignature(), metrics);
        verify(okHandler).appendLog(eq(okCtx), any(LogEntry.class));
        verify(okBreaker).recordSuccess();
    }

    private LogCollectConfig baseConfig() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setUseBuffer(false);
        config.setAsync(false);
        config.setEnableMetrics(true);
        config.setPipelineEnabled(false);
        return config;
    }

    private LogEntry entry(String content) {
        return LogEntry.builder()
                .traceId("trace-entry")
                .content(content)
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.biz.Logger")
                .build();
    }

    private LogEvent mockEvent(String formattedMessage,
                               String template,
                               Object[] args,
                               String loggerName,
                               Level level,
                               Map<String, String> mdc,
                               Throwable throwable) {
        LogEvent event = mock(LogEvent.class);
        Message message = mock(Message.class);
        when(event.getMessage()).thenReturn(message);
        when(message.getFormattedMessage()).thenReturn(formattedMessage);
        when(message.getFormat()).thenReturn(template);
        when(message.getParameters()).thenReturn(args);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getLevel()).thenReturn(level);
        when(event.getThreadName()).thenReturn("main");
        when(event.getTimeMillis()).thenReturn(System.currentTimeMillis());
        when(event.getThrown()).thenReturn(throwable);

        ReadOnlyStringMap contextData = mock(ReadOnlyStringMap.class);
        Map<String, String> payload = mdc == null ? new HashMap<String, String>() : mdc;
        when(contextData.getValue(anyString())).thenAnswer(invocation -> payload.get(invocation.getArgument(0)));
        when(contextData.toMap()).thenReturn(payload);
        when(event.getContextData()).thenReturn(contextData);
        return event;
    }

    private LogCollectContext newContext(String traceId,
                                         LogCollectConfig config,
                                         LogCollectHandler handler,
                                         Object buffer,
                                         Object breaker,
                                         CollectMode mode) throws Exception {
        Method method = LogCollectLog4j2AppenderCoverageGapTest.class.getDeclaredMethod("marker");
        return new LogCollectContext(
                traceId,
                method,
                new Object[0],
                config,
                handler,
                buffer,
                breaker,
                mode);
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    @SuppressWarnings("unused")
    private static void marker() {
    }
}
