package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectRunnableWrapper;

import javax.servlet.*;

public class JavaxLogCollectAsyncContextWrapper implements AsyncContext {

    private final AsyncContext delegate;
    private final LogCollectContextSnapshot snapshot;

    public JavaxLogCollectAsyncContextWrapper(AsyncContext delegate, LogCollectContextSnapshot snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot == null ? LogCollectContextSnapshot.EMPTY : snapshot;
    }

    @Override
    public ServletRequest getRequest() {
        return delegate.getRequest();
    }

    @Override
    public ServletResponse getResponse() {
        return delegate.getResponse();
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return delegate.hasOriginalRequestAndResponse();
    }

    @Override
    public void dispatch() {
        delegate.dispatch();
    }

    @Override
    public void dispatch(String path) {
        delegate.dispatch(path);
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        delegate.dispatch(context, path);
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    @Override
    public void start(Runnable run) {
        if (run == null || snapshot.isEmpty()) {
            delegate.start(run);
            return;
        }
        delegate.start(new LogCollectRunnableWrapper(run, snapshot));
    }

    @Override
    public void addListener(AsyncListener listener) {
        delegate.addListener(listener);
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest servletRequest, ServletResponse servletResponse) {
        delegate.addListener(listener, servletRequest, servletResponse);
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws javax.servlet.ServletException {
        return delegate.createListener(clazz);
    }

    @Override
    public void setTimeout(long timeout) {
        delegate.setTimeout(timeout);
    }

    @Override
    public long getTimeout() {
        return delegate.getTimeout();
    }
}
