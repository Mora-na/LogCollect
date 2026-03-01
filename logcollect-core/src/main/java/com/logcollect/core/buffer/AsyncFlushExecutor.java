package com.logcollect.core.buffer;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    public static void submitOrRun(Runnable task) {
        try {
            EXECUTOR.submit(task);
        } catch (RejectedExecutionException e) {
            LogCollectInternalLogger.warn("Async flush rejected, fallback to sync");
            task.run();
        }
    }
}
