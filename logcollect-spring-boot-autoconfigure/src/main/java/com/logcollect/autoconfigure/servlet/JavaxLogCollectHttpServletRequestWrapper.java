package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;

import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

public class JavaxLogCollectHttpServletRequestWrapper extends HttpServletRequestWrapper {

    public JavaxLogCollectHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return wrapAsyncContext(super.startAsync());
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException {
        return wrapAsyncContext(super.startAsync(servletRequest, servletResponse));
    }

    @Override
    public AsyncContext getAsyncContext() {
        AsyncContext asyncContext = super.getAsyncContext();
        if (asyncContext == null) {
            return null;
        }
        LogCollectServletAsyncSupport.AsyncState state = LogCollectServletAsyncSupport.getAsyncState(this);
        LogCollectContextSnapshot snapshot = state == null ? LogCollectContextSnapshot.EMPTY : state.snapshot();
        return new JavaxLogCollectAsyncContextWrapper(asyncContext, snapshot);
    }

    private AsyncContext wrapAsyncContext(AsyncContext asyncContext) {
        LogCollectServletAsyncSupport.AsyncState state = LogCollectServletAsyncSupport.getOrCreateAsyncState(this);
        LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
        if (state != null) {
            state.updateSnapshot(snapshot);
            asyncContext.addListener(new JavaxLogCollectAsyncListener(state));
        }
        return new JavaxLogCollectAsyncContextWrapper(asyncContext, snapshot);
    }
}
