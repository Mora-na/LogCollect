package com.logcollect.core.context;

import java.util.concurrent.ExecutorService;

public final class LogCollectExecutors {

    private LogCollectExecutors() {
    }

    public static ExecutorService wrap(ExecutorService delegate) {
        return new LogCollectExecutorServiceWrapper(delegate);
    }
}
