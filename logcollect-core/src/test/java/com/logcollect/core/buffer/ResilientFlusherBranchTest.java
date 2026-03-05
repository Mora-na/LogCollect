package com.logcollect.core.buffer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientFlusherBranchTest {

    @Test
    void flush_whenActionThrowsError_shouldRethrow() {
        ResilientFlusher flusher = new ResilientFlusher();
        assertThatThrownBy(() -> flusher.flush(() -> {
            throw new AssertionError("fatal");
        }, () -> "snapshot"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void flushBatch_asyncRetry_thenSuccess_shouldInvokeOnSuccess() throws Exception {
        ResilientFlusher flusher = new ResilientFlusher();
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch exhaustedLatch = new CountDownLatch(1);

        flusher.flushBatch(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("retry");
            }
        }, successLatch::countDown, exhaustedLatch::countDown, () -> "data", false);

        assertThat(successLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exhaustedLatch.getCount()).isEqualTo(1L);
        assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void flushBatch_asyncRetryExhausted_shouldInvokeOnExhausted() throws Exception {
        ResilientFlusher flusher = new ResilientFlusher();
        CountDownLatch exhaustedLatch = new CountDownLatch(1);

        flusher.flushBatch(() -> {
            throw new RuntimeException("always-fail");
        }, null, exhaustedLatch::countDown, () -> "dump-me", false);

        assertThat(exhaustedLatch.await(8, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void flushBatch_async_whenActionThrowsError_shouldRethrow() {
        ResilientFlusher flusher = new ResilientFlusher();
        assertThatThrownBy(() -> flusher.flushBatch(() -> {
            throw new AssertionError("fatal");
        }, null, null, () -> "data", false))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void awaitBackoffDelay_privateBranch_withZeroDelay_shouldReturnTrue() throws Exception {
        ResilientFlusher flusher = new ResilientFlusher();
        Method awaitBackoffDelay = ResilientFlusher.class.getDeclaredMethod("awaitBackoffDelay", long.class);
        awaitBackoffDelay.setAccessible(true);
        Object result = awaitBackoffDelay.invoke(flusher, 0L);
        assertThat(result).isEqualTo(Boolean.TRUE);
    }
}
