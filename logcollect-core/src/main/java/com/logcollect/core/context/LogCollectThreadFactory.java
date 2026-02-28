package com.logcollect.core.context;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认线程工厂实现。
 *
 * <p>创建出的线程会自动包装 Runnable 以传播 LogCollect 上下文，
 * 并使用统一前缀+自增序号命名，便于日志与监控排查。
 */
public class LogCollectThreadFactory implements ThreadFactory {
    /** 线程名前缀。 */
    private final String namePrefix;
    /** 线程序号计数器。 */
    private final AtomicInteger index = new AtomicInteger(0);

    /**
     * @param namePrefix 线程名前缀；为 null 时默认使用 {@code logcollect}
     */
    public LogCollectThreadFactory(String namePrefix) {
        this.namePrefix = namePrefix == null ? "logcollect" : namePrefix;
    }

    /**
     * 创建线程并自动包装上下文传播逻辑。
     */
    @Override
    public Thread newThread(Runnable r) {
        Runnable wrapped = LogCollectContextUtils.wrapRunnable(r);
        Thread t = new Thread(wrapped, namePrefix + "-" + index.incrementAndGet());
        return t;
    }
}
