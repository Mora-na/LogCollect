package com.logcollect.core.buffer;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聚合/单条缓冲区的异步 flush 执行器。
 *
 * <p>线程池饱和时执行分级降级，避免业务线程被 {@code CallerRunsPolicy} 阻塞。
 */
public final class AsyncFlushExecutor {

    public interface RejectedAwareTask extends Runnable {
        /**
         * 返回降级后的重试任务；返回 null 表示无可重试任务。
         */
        Runnable downgradeForRetry();

        /**
         * 最终丢弃时回调。
         */
        void onDiscard(String reason);
    }

    private static final AtomicLong REJECTED_COUNT = new AtomicLong(0);

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
            new LogCollectRejectedHandler());

    private AsyncFlushExecutor() {
    }

    /**
     * 提交 flush 任务。
     *
     * @param task flush 任务
     */
    public static void submitOrRun(Runnable task) {
        if (task == null) {
            return;
        }
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException e) {
            handleRejected(task, EXECUTOR, "executor_rejected");
        }
    }

    public static long getRejectedCount() {
        return REJECTED_COUNT.get();
    }

    public static void shutdownAndAwait(long timeoutMs) {
        EXECUTOR.shutdown();
        if (timeoutMs <= 0) {
            return;
        }
        try {
            if (!EXECUTOR.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EXECUTOR.shutdownNow();
        }
    }

    private static final class LogCollectRejectedHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            handleRejected(r, executor, "async_queue_full");
        }
    }

    private static void handleRejected(Runnable task,
                                       ThreadPoolExecutor executor,
                                       String reason) {
        REJECTED_COUNT.incrementAndGet();
        if (task instanceof RejectedAwareTask) {
            RejectedAwareTask awareTask = (RejectedAwareTask) task;
            Runnable downgraded = awareTask.downgradeForRetry();
            if (downgraded != null && executor != null && executor.getQueue().offer(downgraded)) {
                return;
            }
            awareTask.onDiscard(reason);
        }
        LogCollectInternalLogger.warn("Async flush rejected, task discarded. reason={}, rejectedCount={}",
                reason, REJECTED_COUNT.get());
        if (executor != null && executor.isShutdown()) {
            try {
                task.run();
            } catch (Exception ex) {
                LogCollectInternalLogger.warn("Run async flush task after shutdown failed", ex);
            } catch (Error e) {
                throw e;
            }
        }
    }
}
