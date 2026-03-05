package com.logcollect.core.buffer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class AsyncFlushExecutorBranchTest {

    @Test
    void rejectedAwareTask_defaultMethods_shouldReturnNullAndNoThrow() {
        AsyncFlushExecutor.RejectedAwareTask task = () -> {
        };
        assertThat(task.downgradeForRetry()).isNull();
        assertThatCode(() -> task.onDiscard("discarded")).doesNotThrowAnyException();
    }

    @Test
    void submitOrRun_whenExecutorRejects_shouldFallbackAndIncreaseRejectedCount() throws Exception {
        ThreadPoolExecutor rejecting = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1),
                new ThreadPoolExecutor.AbortPolicy());
        ThreadPoolExecutor old = swapExecutor(rejecting);
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            AsyncFlushExecutor.submitOrRun(() -> awaitLatch(blocker));
            AsyncFlushExecutor.submitOrRun(() -> awaitLatch(blocker));

            long before = AsyncFlushExecutor.getRejectedCount();
            AtomicBoolean fallbackExecuted = new AtomicBoolean(false);
            AsyncFlushExecutor.submitOrRun(() -> fallbackExecuted.set(true));

            assertThat(fallbackExecuted.get()).isTrue();
            assertThat(AsyncFlushExecutor.getRejectedCount()).isGreaterThan(before);
        } finally {
            blocker.countDown();
            rejecting.shutdownNow();
            restoreExecutor(old);
        }
    }

    @Test
    void submitOrRun_whenRejectedAndExecutorBecomesShutdown_shouldRunShutdownFallback() throws Exception {
        ThreadPoolExecutor shutdownRejecting = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1)) {
            @Override
            public void execute(Runnable command) {
                shutdown();
                throw new RejectedExecutionException("forced");
            }
        };
        ThreadPoolExecutor old = swapExecutor(shutdownRejecting);
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            AsyncFlushExecutor.submitOrRun(() -> ran.set(true));
            assertThat(ran.get()).isTrue();
        } finally {
            shutdownRejecting.shutdownNow();
            restoreExecutor(old);
        }
    }

    @Test
    void resizeAndShutdownBranches_shouldBeCovered() {
        try {
            AsyncFlushExecutor.configure(1, 1, 8);
            AsyncFlushExecutor.resize(0, 0);
            AsyncFlushExecutor.shutdownAndAwait(0);
        } finally {
            AsyncFlushExecutor.configure(2, 4, 4096);
        }
    }

    @Test
    void shutdownAndAwait_whenInterrupted_shouldPreserveInterruptFlag() {
        try {
            AsyncFlushExecutor.configure(1, 1, 8);
            Thread.currentThread().interrupt();
            AsyncFlushExecutor.shutdownAndAwait(500);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            AsyncFlushExecutor.configure(2, 4, 4096);
        }
    }

    @Test
    void runSafely_privateMethod_shouldSwallowExceptionAndRethrowError() throws Exception {
        Method runSafely = AsyncFlushExecutor.class.getDeclaredMethod("runSafely", Runnable.class, String.class);
        runSafely.setAccessible(true);

        assertThatCode(() -> runSafely.invoke(null, (Runnable) () -> {
            throw new RuntimeException("runtime");
        }, "reason")).doesNotThrowAnyException();

        assertThatThrownBy(() -> runSafely.invoke(null, (Runnable) () -> {
            throw new AssertionError("fatal");
        }, "reason"))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolExecutor swapExecutor(ThreadPoolExecutor replacement) throws Exception {
        Field field = AsyncFlushExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        ThreadPoolExecutor previous = (ThreadPoolExecutor) field.get(null);
        field.set(null, replacement);
        return previous;
    }

    private static void restoreExecutor(ThreadPoolExecutor executor) throws Exception {
        Field field = AsyncFlushExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(null, executor);
    }
}
