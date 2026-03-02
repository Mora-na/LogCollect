package com.logcollect.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class RegexSafetyValidatorExecutorEdgeTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void isSafe_interruptedAfterSubmit_returnsFalseAndCancelsFuture() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<Boolean> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return future;
        });

        boolean safe = withExecutor(replacement, () -> RegexSafetyValidator.isSafe("a+"));

        assertThat(safe).isFalse();
        verify(future).cancel(true);
    }

    @Test
    void isSafe_futureGetInterrupted_returnsFalseAndKeepsInterruptFlag() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<Boolean> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("interrupted"));

        boolean safe = withExecutor(replacement, () -> RegexSafetyValidator.isSafe("a+"));

        assertThat(safe).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void isSafe_futureGetExecutionException_returnsFalse() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<Boolean> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.ExecutionException(new IllegalStateException("boom")));

        boolean safe = withExecutor(replacement, () -> RegexSafetyValidator.isSafe("a+"));

        assertThat(safe).isFalse();
    }

    @Test
    void isSafe_futureGetThrowsRuntimeException_returnsFalse() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<Boolean> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new IllegalStateException("rejected"));

        boolean safe = withExecutor(replacement, () -> RegexSafetyValidator.isSafe("a+"));

        assertThat(safe).isFalse();
    }

    @Test
    void isSafe_submitThrowsError_rethrows() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        when(replacement.submit(any(Callable.class))).thenThrow(new AssertionError("fatal"));

        assertThatThrownBy(() -> withExecutor(replacement, () -> RegexSafetyValidator.isSafe("a+")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fatal");
    }

    private static <T> T withExecutor(ExecutorService replacement, ThrowingSupplier<T> action) throws Exception {
        ExecutorService original = RegexSafetyValidator.EXECUTOR;
        RegexSafetyValidator.EXECUTOR = replacement;
        try {
            return action.get();
        } finally {
            RegexSafetyValidator.EXECUTOR = original;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
