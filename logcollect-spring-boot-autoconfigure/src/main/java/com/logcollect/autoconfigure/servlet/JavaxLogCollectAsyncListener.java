package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import java.io.IOException;
import java.util.Deque;

public class JavaxLogCollectAsyncListener implements AsyncListener {

    private final Deque<LogCollectContext> snapshot;

    public JavaxLogCollectAsyncListener(Deque<LogCollectContext> snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void onComplete(AsyncEvent event) throws IOException {
        LogCollectContextManager.clear();
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException {
        LogCollectContextManager.clear();
    }

    @Override
    public void onError(AsyncEvent event) throws IOException {
        LogCollectContextManager.clear();
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException {
        LogCollectContextManager.restore(snapshot);
    }
}
