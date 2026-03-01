package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;

import java.io.IOException;
import java.util.Deque;

public class JakartaLogCollectAsyncListener implements AsyncListener {

    private final Deque<LogCollectContext> snapshot;

    public JakartaLogCollectAsyncListener(Deque<LogCollectContext> snapshot) {
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
