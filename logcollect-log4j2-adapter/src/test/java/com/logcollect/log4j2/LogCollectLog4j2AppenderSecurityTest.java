package com.logcollect.log4j2;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.context.LogCollectContextManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LogCollectLog4j2AppenderSecurityTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void append_noMdcTraceId_skipped() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-1", handler));

        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("test", null);
        appender.start();
        LogEvent event = mockEvent("message", "com.test.Service", Level.INFO, new HashMap<String, String>());
        appender.append(event);

        verify(handler, never()).appendLog(any(), any());
        LogCollectContextManager.pop();
    }

    @Test
    void append_mdcTraceIdPresent_collected() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-2", handler));

        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("test", null);
        appender.start();
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-2");
        LogEvent event = mockEvent("message", "com.test.Service", Level.INFO, mdc);
        appender.append(event);

        verify(handler, times(1)).appendLog(any(LogCollectContext.class), any(LogEntry.class));
        LogCollectContextManager.pop();
    }

    @Test
    void append_internalLogger_alwaysSkipped() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContextManager.push(newContext("trace-3", handler));

        LogCollectLog4j2Appender appender = new LogCollectLog4j2Appender("test", null);
        appender.start();
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put(LogCollectContextManager.TRACE_ID_KEY, "trace-3");
        LogEvent event = mockEvent("message", "com.logcollect.internal.test", Level.INFO, mdc);
        appender.append(event);

        verify(handler, never()).appendLog(any(), any());
        LogCollectContextManager.pop();
    }

    private LogEvent mockEvent(String message, String logger, Level level, Map<String, String> mdc) {
        LogEvent event = mock(LogEvent.class);
        Message msg = mock(Message.class);
        when(msg.getFormattedMessage()).thenReturn(message);
        when(event.getMessage()).thenReturn(msg);
        when(event.getLoggerName()).thenReturn(logger);
        when(event.getLevel()).thenReturn(level);
        when(event.getThreadName()).thenReturn("main");
        when(event.getTimeMillis()).thenReturn(System.currentTimeMillis());

        ReadOnlyStringMap map = mock(ReadOnlyStringMap.class);
        when(map.getValue(LogCollectContextManager.TRACE_ID_KEY)).thenReturn(mdc.get(LogCollectContextManager.TRACE_ID_KEY));
        when(map.toMap()).thenReturn(mdc);
        when(event.getContextData()).thenReturn(map);
        return event;
    }

    private LogCollectContext newContext(String traceId, LogCollectHandler handler) {
        try {
            LogCollectConfig config = LogCollectConfig.frameworkDefaults();
            config.setUseBuffer(false);
            config.setAsync(false);
            config.setEnableMetrics(false);
            Method method = LogCollectLog4j2AppenderSecurityTest.class.getDeclaredMethod("marker");
            return new LogCollectContext(traceId, method, new Object[0], config, handler, null, null, CollectMode.SINGLE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void marker() {
    }
}
