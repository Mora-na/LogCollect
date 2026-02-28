package com.logcollect.core.context;

import java.util.concurrent.ThreadFactory;

/**
 * 线程工厂包装器。
 *
 * <p>用于在不改动现有线程工厂实现的前提下，为其注入 LogCollect 上下文传播能力。
 */
public class LogCollectThreadFactoryWrapper implements ThreadFactory {
    /** 原始线程工厂。 */
    private final ThreadFactory delegate;

    /**
     * @param delegate 原始线程工厂
     */
    public LogCollectThreadFactoryWrapper(ThreadFactory delegate) {
        this.delegate = delegate;
    }

    /**
     * 创建线程前先包装任务，确保新线程执行时可恢复父线程上下文。
     */
    @Override
    public Thread newThread(Runnable r) {
        Runnable wrapped = LogCollectContextUtils.wrapRunnable(r);
        return delegate.newThread(wrapped);
    }
}
