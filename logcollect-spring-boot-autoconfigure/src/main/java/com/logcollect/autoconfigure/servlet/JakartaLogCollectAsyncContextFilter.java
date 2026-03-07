package com.logcollect.autoconfigure.servlet;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

public class JakartaLogCollectAsyncContextFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ServletRequest requestToUse = request;
        if (request instanceof HttpServletRequest) {
            requestToUse = new JakartaLogCollectHttpServletRequestWrapper((HttpServletRequest) request);
        }
        LogCollectServletAsyncSupport.bindCurrentRequest(requestToUse);
        try {
            chain.doFilter(requestToUse, response);
        } finally {
            LogCollectServletAsyncSupport.clearCurrentRequest();
        }
    }

    @Override
    public void destroy() {
        // no-op
    }
}
