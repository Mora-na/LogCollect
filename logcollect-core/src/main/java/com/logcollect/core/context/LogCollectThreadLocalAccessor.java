package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContextSnapshot;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * Micrometer ContextPropagation 适配器。
 *
 * <p>用于将 LogCollect 的 ThreadLocal 上下文注册到
 * {@link io.micrometer.context.ContextRegistry}，让 Spring/Reactor 场景自动传播。
 */
public class LogCollectThreadLocalAccessor implements ThreadLocalAccessor<LogCollectContextSnapshot> {
    /** 在 ContextPropagation 框架中的唯一键。 */
    public static final String KEY = "logcollect.context";

    /** 返回注册键名。 */
    @Override
    public Object key() {
        return KEY;
    }

    /** 从当前线程捕获 LogCollect 上下文快照。 */
    @Override
    public LogCollectContextSnapshot getValue() {
        return LogCollectContextManager.captureSnapshot();
    }

    /** 在当前线程恢复快照。 */
    @Override
    public void setValue(LogCollectContextSnapshot value) {
        if (value != null) {
            LogCollectContextManager.restoreSnapshot(value);
        }
    }

    /**
     * ContextPropagation 在某些实现中会调用无参 setValue。
     *
     * <p>无值场景主动清理，避免上下文泄漏。
     */
    @Override
    public void setValue() {
        LogCollectContextManager.clearSnapshotContext();
    }

    /** 清空当前线程中的 LogCollect 上下文。 */
    @Override
    public void reset() {
        LogCollectContextManager.clearSnapshotContext();
    }
}
