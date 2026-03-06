package com.logcollect.core.buffer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void submitOrRun_whenQueueIsSaturated_shouldFallbackAndIncreaseRejectedCount() throws Exception {
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            AsyncFlushExecutor.configure(1, 1, 1);
            AsyncFlushExecutor.submitOrRun(() -> awaitLatch(blocker));
            AsyncFlushExecutor.submitOrRun(() -> awaitLatch(blocker));

            long before = AsyncFlushExecutor.getRejectedCount();
            AtomicBoolean fallbackExecuted = new AtomicBoolean(false);
            AsyncFlushExecutor.submitOrRun(() -> fallbackExecuted.set(true));

            assertThat(fallbackExecuted.get()).isTrue();
            assertThat(AsyncFlushExecutor.getRejectedCount()).isGreaterThan(before);
        } finally {
            blocker.countDown();
            AsyncFlushExecutor.configure(2, 4, 4096);
        }
    }

    @Test
    void submitOrRun_whenExecutorShutdown_shouldRunOnCallerThread() {
        try {
            AsyncFlushExecutor.configure(1, 1, 8);
            AsyncFlushExecutor.shutdownAndAwait(200);
            AtomicBoolean ran = new AtomicBoolean(false);
            AsyncFlushExecutor.submitOrRun(() -> ran.set(true));
            assertThat(ran.get()).isTrue();
        } finally {
            AsyncFlushExecutor.configure(2, 4, 4096);
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

}
