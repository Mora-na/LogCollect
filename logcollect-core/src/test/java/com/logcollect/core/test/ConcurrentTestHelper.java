package com.logcollect.core.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发测试辅助：N 个线程同时执行操作，验证线程安全性。
 */
public final class ConcurrentTestHelper {
    private ConcurrentTestHelper() {
    }

    public static void runConcurrently(int threadCount,
                                       Runnable action,
                                       Duration timeout) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicReference<Throwable> firstError = new AtomicReference<Throwable>();
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        action.run();
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            go.countDown();
            assertThat(done.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    .as("并发执行应在超时内完成")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        if (firstError.get() != null) {
            throw new AssertionError("并发执行出错", firstError.get());
        }
    }

    public static <T> List<T> collectConcurrently(int threadCount,
                                                  Callable<T> action,
                                                  Duration timeout) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<Future<T>>(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return action.call();
                }));
            }

            ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            go.countDown();

            List<T> results = new ArrayList<T>(threadCount);
            for (Future<T> f : futures) {
                results.add(f.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
