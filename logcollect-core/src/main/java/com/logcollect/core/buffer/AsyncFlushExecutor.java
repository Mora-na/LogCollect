package com.logcollect.core.buffer;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 聚合/单条缓冲区的异步 flush 执行器。
 *
 * <p>当线程池饱和时回退到调用线程同步执行，确保 flush 不被丢弃。
 */
public final class AsyncFlushExecutor {

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(1000),
            r -> {
                Thread thread = new Thread(r, "logcollect-async-flush");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private AsyncFlushExecutor() {
    }

    /**
     * 提交 flush 任务；若线程池拒绝则在当前线程直接执行。
     *
     * @param task flush 任务
     */
    public static void submitOrRun(Runnable task) {
        try {
            EXECUTOR.submit(task);
        } catch (RejectedExecutionException e) {
            LogCollectInternalLogger.warn("Async flush rejected, fallback to sync");
            task.run();
        }
    }
}
