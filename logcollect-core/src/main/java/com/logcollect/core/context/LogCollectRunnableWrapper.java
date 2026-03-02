package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContextSnapshot;

/**
 * Runnable 上下文包装器。
 *
 * <p>在子线程执行前恢复父线程快照，执行后无条件清理，确保线程复用场景安全。
 */
public class LogCollectRunnableWrapper implements Runnable {
    /** 被包装的原始任务。 */
    private final Runnable delegate;
    /** 从父线程捕获的上下文快照。 */
    private final LogCollectContextSnapshot snapshot;

    /**
     * @param delegate 原始任务
     * @param snapshot 父线程快照
     */
    public LogCollectRunnableWrapper(Runnable delegate, LogCollectContextSnapshot snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    /**
     * 恢复上下文后执行原任务，结束时清理上下文。
     */
    @Override
    public void run() {
        LogCollectContextManager.restoreSnapshot(snapshot);
        try {
            delegate.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } finally {
            LogCollectContextManager.clearSnapshotContext();
        }
    }
}
