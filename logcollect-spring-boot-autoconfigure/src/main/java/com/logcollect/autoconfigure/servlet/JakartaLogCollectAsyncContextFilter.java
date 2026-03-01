package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;
import jakarta.servlet.*;

import java.io.IOException;
import java.util.Deque;

public class JakartaLogCollectAsyncContextFilter implements Filter {

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
        request.getAsyncContext().addListener(new JakartaLogCollectAsyncListener(snapshot));
    }

    @Override
    public void destroy() {
        // no-op
    }
}
