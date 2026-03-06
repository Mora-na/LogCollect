package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogCollectAsyncContextScenarioTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void javaxServletAsyncContext_shouldRegisterListenerAndRestoreSnapshot() throws Exception {
        JavaxLogCollectAsyncContextFilter filter = new JavaxLogCollectAsyncContextFilter();
        javax.servlet.ServletRequest request = mock(javax.servlet.ServletRequest.class);
        javax.servlet.ServletResponse response = mock(javax.servlet.ServletResponse.class);
        javax.servlet.FilterChain chain = mock(javax.servlet.FilterChain.class);
        javax.servlet.AsyncContext asyncContext = mock(javax.servlet.AsyncContext.class);

        when(request.isAsyncStarted()).thenReturn(true);
        when(request.getAsyncContext()).thenReturn(asyncContext);

        String traceId = "trace-javax-servlet";
        LogCollectContextManager.push(newContext(traceId));
        try {
            filter.doFilter(request, response, chain);
        } finally {
            LogCollectContextManager.clear();
        }

        verify(chain).doFilter(request, response);
        ArgumentCaptor<javax.servlet.AsyncListener> listenerCaptor =
                ArgumentCaptor.forClass(javax.servlet.AsyncListener.class);
        verify(asyncContext).addListener(listenerCaptor.capture());

        javax.servlet.AsyncListener listener = listenerCaptor.getValue();
        assertThat(listener).isInstanceOf(JavaxLogCollectAsyncListener.class);

        listener.onStartAsync(null);
        assertThat(LogCollectContextManager.current()).isNotNull();
        assertThat(LogCollectContextManager.current().getTraceId()).isEqualTo(traceId);

        listener.onComplete(null);
        assertThat(LogCollectContextManager.current()).isNull();
    }

    @Test
    void jakartaServletAsyncContext_shouldRegisterListenerAndRestoreSnapshot() throws Exception {
        JakartaLogCollectAsyncContextFilter filter = new JakartaLogCollectAsyncContextFilter();
        jakarta.servlet.ServletRequest request = mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse response = mock(jakarta.servlet.ServletResponse.class);
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
        jakarta.servlet.AsyncContext asyncContext = mock(jakarta.servlet.AsyncContext.class);

        when(request.isAsyncStarted()).thenReturn(true);
        when(request.getAsyncContext()).thenReturn(asyncContext);

        String traceId = "trace-jakarta-servlet";
        LogCollectContextManager.push(newContext(traceId));
        try {
            filter.doFilter(request, response, chain);
        } finally {
            LogCollectContextManager.clear();
        }

        verify(chain).doFilter(request, response);
        ArgumentCaptor<jakarta.servlet.AsyncListener> listenerCaptor =
                ArgumentCaptor.forClass(jakarta.servlet.AsyncListener.class);
        verify(asyncContext).addListener(listenerCaptor.capture());

        jakarta.servlet.AsyncListener listener = listenerCaptor.getValue();
        assertThat(listener).isInstanceOf(JakartaLogCollectAsyncListener.class);

        listener.onStartAsync(null);
        assertThat(LogCollectContextManager.current()).isNotNull();
        assertThat(LogCollectContextManager.current().getTraceId()).isEqualTo(traceId);

        listener.onComplete(null);
        assertThat(LogCollectContextManager.current()).isNull();
    }

    private static LogCollectContext newContext(String traceId) {
        return new LogCollectContext(traceId, null, null, null, null, null, null, null);
    }
}
