package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import java.io.IOException;

public class LogCollectAsyncContextListener implements AsyncListener {
    private final LogCollectContextSnapshot snapshot;

    public LogCollectAsyncContextListener(LogCollectContextSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void onComplete(AsyncEvent event) throws IOException {
        LogCollectContextManager.clearSnapshotContext();
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException {
        LogCollectContextManager.clearSnapshotContext();
    }

    @Override
    public void onError(AsyncEvent event) throws IOException {
        LogCollectContextManager.clearSnapshotContext();
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException {
        LogCollectContextManager.restoreSnapshot(snapshot);
    }
}
