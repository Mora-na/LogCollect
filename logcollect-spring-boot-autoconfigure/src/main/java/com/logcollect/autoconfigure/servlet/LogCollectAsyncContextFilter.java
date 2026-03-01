package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;

import javax.servlet.*;
import java.io.IOException;
import java.util.Deque;

public class LogCollectAsyncContextFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
        if (!request.isAsyncStarted()) {
            return;
        }
        Deque<LogCollectContext> snapshot = LogCollectContextManager.snapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                LogCollectContextManager.clear();
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                LogCollectContextManager.clear();
            }

            @Override
            public void onError(AsyncEvent event) {
                LogCollectContextManager.clear();
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                LogCollectContextManager.restore(snapshot);
            }
        });
    }

    @Override
    public void destroy() {
        // no-op
    }
}
