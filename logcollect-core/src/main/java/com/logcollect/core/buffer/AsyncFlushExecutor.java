package com.logcollect.core.buffer;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聚合/单条缓冲区的异步 flush 执行器。
 *
 * <p>默认使用 {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy}，
 * 在线程池饱和时由调用线程执行任务，提供自然反压并避免数据丢失。
 */
public final class AsyncFlushExecutor {

    public interface RejectedAwareTask extends Runnable {
        default Runnable downgradeForRetry() {
            return null;
        }

        default void onDiscard(String reason) {
        }
    }

    private static final AtomicLong REJECTED_COUNT = new AtomicLong(0);

    private static final Object EXECUTOR_LOCK = new Object();
    private static volatile ThreadPoolExecutor executor = newExecutor(
            defaultCoreThreads(),
            defaultMaxThreads(),
            4096);

    private AsyncFlushExecutor() {
    }

    public static void submitOrRun(Runnable task) {
        if (task == null) {
            return;
        }
        ThreadPoolExecutor current = executor;
        if (current.isShutdown()) {
            runSafely(task, "executor_shutdown");
            return;
        }
        try {
            current.execute(task);
        } catch (RejectedExecutionException e) {
            REJECTED_COUNT.incrementAndGet();
            if (current.isShutdown()) {
                runSafely(task, "executor_shutdown");
                return;
            }
            // CallerRunsPolicy 在正常运行时不应触发该分支，兜底保护。
            runSafely(task, "executor_rejected_fallback");
        }
    }

    public static long getRejectedCount() {
        return REJECTED_COUNT.get();
    }

    public static void configure(int coreThreads, int maxThreads, int queueCapacity) {
        int normalizedCore = Math.max(1, coreThreads);
        int normalizedMax = Math.max(normalizedCore, maxThreads);
        int normalizedQueue = Math.max(1, queueCapacity);
        ThreadPoolExecutor replacement = newExecutor(normalizedCore, normalizedMax, normalizedQueue);
        ThreadPoolExecutor old;
        synchronized (EXECUTOR_LOCK) {
            old = executor;
            executor = replacement;
        }
        if (old != null) {
            old.shutdown();
        }
    }

    public static void resize(int coreThreads, int maxThreads) {
        ThreadPoolExecutor current = executor;
        int normalizedCore = Math.max(1, coreThreads);
        int normalizedMax = Math.max(normalizedCore, maxThreads);
        current.setMaximumPoolSize(normalizedMax);
        current.setCorePoolSize(normalizedCore);
    }

    public static void shutdownAndAwait(long timeoutMs) {
        ThreadPoolExecutor current = executor;
        current.shutdown();
        if (timeoutMs <= 0) {
            return;
        }
        try {
            if (!current.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }

    private static ThreadPoolExecutor newExecutor(int coreThreads, int maxThreads, int queueCapacity) {
        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(queueCapacity),
                r -> {
                    Thread thread = new Thread(r, "logcollect-flush-" + threadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static int defaultCoreThreads() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    }

    private static int defaultMaxThreads() {
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    private static void runSafely(Runnable task, String reason) {
        try {
            task.run();
        } catch (Exception ex) {
            LogCollectInternalLogger.warn("Run async flush task failed, reason={}", reason, ex);
        } catch (Error e) {
            throw e;
        }
    }
}
