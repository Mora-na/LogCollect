package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContextSnapshot;

import java.util.concurrent.Callable;

/**
 * Callable 上下文包装器。
 *
 * <p>用于在线程池提交 Callable 时传播父线程上下文。
 *
 * @param <V> Callable 返回值类型
 */
public class LogCollectCallableWrapper<V> implements Callable<V> {
    /** 被包装的原始 Callable。 */
    private final Callable<V> delegate;
    /** 从父线程捕获的上下文快照。 */
    private final LogCollectContextSnapshot snapshot;

    /**
     * @param delegate 原始 Callable
     * @param snapshot 父线程快照
     */
    public LogCollectCallableWrapper(Callable<V> delegate, LogCollectContextSnapshot snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    /**
     * 恢复上下文后调用原任务，结束时清理上下文。
     *
     * @return 原任务返回值
     * @throws Exception 原任务抛出的受检异常
     */
    @Override
    public V call() throws Exception {
        LogCollectContextManager.restoreSnapshot(snapshot);
        try {
            return delegate.call();
        } catch (Throwable t) {
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        } finally {
            LogCollectContextManager.clearSnapshotContext();
        }
    }
}
