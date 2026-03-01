package com.logcollect.core.context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public final class LogCollectExecutors {

    private LogCollectExecutors() {
    }

    public static ExecutorService wrap(ExecutorService delegate) {
        if (delegate instanceof ScheduledExecutorService) {
            return LogCollectContextUtils.wrapScheduledExecutorService((ScheduledExecutorService) delegate);
        }
        return new LogCollectExecutorServiceWrapper(delegate);
    }
}
