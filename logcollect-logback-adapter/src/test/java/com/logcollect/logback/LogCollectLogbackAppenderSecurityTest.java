package com.logcollect.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LogCollectLogbackAppenderSecurityTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void append_noMdcTraceId_skipped() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-1", handler));

        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.start();
        ILoggingEvent event = mockEvent("message", "com.test.Service", Level.INFO, new HashMap<String, String>());
        appender.doAppend(event);

        verify(handler, never()).appendLog(any(), any());
        LogCollectContextManager.pop();
    }

    @Test
    void append_mdcTraceIdPresent_collected() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-2", handler));

        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.start();
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-2");
        ILoggingEvent event = mockEvent("message", "com.test.Service", Level.INFO, mdc);

        appender.doAppend(event);

        verify(handler, times(1)).appendLog(any(LogCollectContext.class), any(LogEntry.class));
        LogCollectContextManager.pop();
    }

    @Test
    void append_mdcTraceIdMismatch_skipped() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-3", handler));

        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.start();
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "another-trace");
        ILoggingEvent event = mockEvent("message", "com.test.Service", Level.INFO, mdc);

        appender.doAppend(event);

        verify(handler, never()).appendLog(any(), any());
        LogCollectContextManager.pop();
    }

    @Test
    void append_internalLogger_alwaysSkipped() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-4", handler));

        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.start();
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-4");
        ILoggingEvent event = mockEvent("message", "com.logcollect.internal.Test", Level.INFO, mdc);

        appender.doAppend(event);

        verify(handler, never()).appendLog(any(), any());
        LogCollectContextManager.pop();
    }

    @Test
    void append_eventFieldsCopied_noReferenceRetained() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-5", handler));

        LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
        appender.start();
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-5");
        mdc.put("requestId", "req-1");
        ILoggingEvent event = mockEvent("message", "com.test.Service", Level.INFO, mdc);

        appender.doAppend(event);
        mdc.put("requestId", "req-changed");

        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(handler).appendLog(any(LogCollectContext.class), captor.capture());
        assertThat(captor.getValue().getMdcContext().get("requestId")).isEqualTo("req-1");
        LogCollectContextManager.pop();
    }

    private ILoggingEvent mockEvent(String msg, String logger, Level level, Map<String, String> mdc) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getMDCPropertyMap()).thenReturn(mdc);
        when(event.getFormattedMessage()).thenReturn(msg);
        when(event.getLoggerName()).thenReturn(logger);
        when(event.getLevel()).thenReturn(level);
        when(event.getThreadName()).thenReturn("main");
        when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
        return event;
    }

    private LogCollectContext newContext(String traceId, LogCollectHandler handler) {
        try {
            LogCollectConfig config = LogCollectConfig.frameworkDefaults();
            config.setUseBuffer(false);
            config.setAsync(false);
            config.setEnableMetrics(false);
            Method method = LogCollectLogbackAppenderSecurityTest.class.getDeclaredMethod("marker");
            return new LogCollectContext(traceId, method, new Object[0], config, handler, null, null, CollectMode.SINGLE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void marker() {
    }
}
