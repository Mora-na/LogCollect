package com.logcollect.autoconfigure.servlet;

import com.logcollect.core.context.LogCollectContextManager;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;

import java.io.IOException;

public class JakartaLogCollectAsyncListener implements AsyncListener {

    private final LogCollectServletAsyncSupport.AsyncState state;

    public JakartaLogCollectAsyncListener(LogCollectServletAsyncSupport.AsyncState state) {
        this.state = state;
    }

    @Override
    public void onComplete(AsyncEvent event) throws IOException {
        state.finish(null);
        LogCollectContextManager.clear();
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException {
        state.finish(new IllegalStateException("Servlet AsyncContext timeout"));
        LogCollectContextManager.clear();
    }

    @Override
    public void onError(AsyncEvent event) throws IOException {
        state.finish(event == null ? null : event.getThrowable());
        LogCollectContextManager.clear();
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException {
        AsyncContext asyncContext = event == null ? null : event.getAsyncContext();
        if (asyncContext != null) {
            asyncContext.addListener(new JakartaLogCollectAsyncListener(state));
        }
        LogCollectContextManager.clear();
    }
}
