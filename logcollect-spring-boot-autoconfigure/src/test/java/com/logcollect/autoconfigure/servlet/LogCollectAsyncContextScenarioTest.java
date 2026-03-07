package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LogCollectAsyncContextScenarioTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
        LogCollectServletAsyncSupport.clearCurrentRequest();
    }

    @Test
    void javaxServletAsyncContext_shouldWrapAsyncTaskAndReRegisterListener() throws Exception {
        JavaxLogCollectAsyncContextFilter filter = new JavaxLogCollectAsyncContextFilter();
        javax.servlet.http.HttpServletRequest request = mock(javax.servlet.http.HttpServletRequest.class);
        javax.servlet.ServletResponse response = mock(javax.servlet.ServletResponse.class);
        javax.servlet.AsyncContext asyncContext = mock(javax.servlet.AsyncContext.class);
        javax.servlet.AsyncContext restartedAsyncContext = mock(javax.servlet.AsyncContext.class);
        javax.servlet.AsyncEvent asyncEvent = mock(javax.servlet.AsyncEvent.class);
        AtomicReference<String> childTraceId = new AtomicReference<String>();

        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
        when(asyncEvent.getAsyncContext()).thenReturn(restartedAsyncContext);

        javax.servlet.FilterChain chain = (req, res) -> {
            javax.servlet.http.HttpServletRequest wrapped = (javax.servlet.http.HttpServletRequest) req;
            wrapped.startAsync().start(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                childTraceId.set(current == null ? null : current.getTraceId());
            });
        };

        String traceId = "trace-javax-servlet";
        LogCollectContextManager.push(newContext(traceId));
        try {
            filter.doFilter(request, response, chain);
        } finally {
            LogCollectContextManager.clear();
        }

        assertThat(childTraceId.get()).isEqualTo(traceId);

        ArgumentCaptor<javax.servlet.AsyncListener> listenerCaptor =
                ArgumentCaptor.forClass(javax.servlet.AsyncListener.class);
        verify(asyncContext).addListener(listenerCaptor.capture());
        javax.servlet.AsyncListener listener = listenerCaptor.getValue();
        listener.onStartAsync(asyncEvent);
        verify(restartedAsyncContext).addListener(any(javax.servlet.AsyncListener.class));
    }

    @Test
    void jakartaServletAsyncContext_shouldWrapAsyncTaskAndReRegisterListener() throws Exception {
        JakartaLogCollectAsyncContextFilter filter = new JakartaLogCollectAsyncContextFilter();
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        jakarta.servlet.ServletResponse response = mock(jakarta.servlet.ServletResponse.class);
        jakarta.servlet.AsyncContext asyncContext = mock(jakarta.servlet.AsyncContext.class);
        jakarta.servlet.AsyncContext restartedAsyncContext = mock(jakarta.servlet.AsyncContext.class);
        jakarta.servlet.AsyncEvent asyncEvent = mock(jakarta.servlet.AsyncEvent.class);
        AtomicReference<String> childTraceId = new AtomicReference<String>();

        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
        when(asyncEvent.getAsyncContext()).thenReturn(restartedAsyncContext);

        jakarta.servlet.FilterChain chain = (req, res) -> {
            jakarta.servlet.http.HttpServletRequest wrapped = (jakarta.servlet.http.HttpServletRequest) req;
            wrapped.startAsync().start(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                childTraceId.set(current == null ? null : current.getTraceId());
            });
        };

        String traceId = "trace-jakarta-servlet";
        LogCollectContextManager.push(newContext(traceId));
        try {
            filter.doFilter(request, response, chain);
        } finally {
            LogCollectContextManager.clear();
        }

        assertThat(childTraceId.get()).isEqualTo(traceId);

        ArgumentCaptor<jakarta.servlet.AsyncListener> listenerCaptor =
                ArgumentCaptor.forClass(jakarta.servlet.AsyncListener.class);
        verify(asyncContext).addListener(listenerCaptor.capture());
        jakarta.servlet.AsyncListener listener = listenerCaptor.getValue();
        listener.onStartAsync(asyncEvent);
        verify(restartedAsyncContext).addListener(any(jakarta.servlet.AsyncListener.class));
    }

    @Test
    void asyncState_shouldRunFinalizerWhenCompletionHappensBeforeRegistration() {
        LogCollectServletAsyncSupport.AsyncState state = new LogCollectServletAsyncSupport.AsyncState();
        AtomicInteger callbackCount = new AtomicInteger(0);
        AtomicReference<Throwable> callbackError = new AtomicReference<Throwable>();

        IOException completionError = new IOException("async-complete-first");
        state.finish(completionError);
        state.registerFinalizer(error -> {
            callbackCount.incrementAndGet();
            callbackError.set(error);
        });

        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(callbackError.get()).isSameAs(completionError);
    }

    private static LogCollectContext newContext(String traceId) {
        return new LogCollectContext(traceId, null, null, null, null, null, null, null);
    }
}
