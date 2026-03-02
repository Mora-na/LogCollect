package com.logcollect.core.context;

import java.util.concurrent.Executor;

/**
 * Executor 上下文包装器。
 */
public class LogCollectExecutorWrapper implements Executor, LogCollectWrappedExecutor {
    private final Executor delegate;

    public LogCollectExecutorWrapper(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(LogCollectContextUtils.wrapRunnable(command));
    }
}
