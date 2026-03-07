package com.logcollect.autoconfigure.servlet;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

public class JavaxLogCollectAsyncContextFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ServletRequest requestToUse = request;
        if (request instanceof HttpServletRequest) {
            requestToUse = new JavaxLogCollectHttpServletRequestWrapper((HttpServletRequest) request);
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
