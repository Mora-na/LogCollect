package com.logcollect.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DefaultLogMaskerExecutorEdgeTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void mask_interruptedAfterSubmit_returnsOriginalAndCancelsFuture() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<String> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return future;
        });

        String input = "phone=13812345678";
        String result = withExecutor(replacement, () -> new DefaultLogMasker().mask(input));

        assertThat(result).isEqualTo(input);
        verify(future).cancel(true);
    }

    @Test
    void mask_futureGetInterrupted_returnsOriginalAndKeepsInterruptFlag() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<String> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("interrupted"));

        String input = "phone=13812345678";
        String result = withExecutor(replacement, () -> new DefaultLogMasker().mask(input));

        assertThat(result).isEqualTo(input);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void mask_futureGetThrowsRuntimeException_returnsOriginal() throws Exception {
        ExecutorService replacement = mock(ExecutorService.class);
        @SuppressWarnings("unchecked")
        Future<String> future = mock(Future.class);
        when(replacement.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new IllegalStateException("boom"));

        String input = "phone=13812345678";
        String result = withExecutor(replacement, () -> new DefaultLogMasker().mask(input));

        assertThat(result).isEqualTo(input);
    }

    private static <T> T withExecutor(ExecutorService replacement, ThrowingSupplier<T> action) throws Exception {
        ExecutorService original = DefaultLogMasker.MASK_EXECUTOR;
        DefaultLogMasker.MASK_EXECUTOR = replacement;
        try {
            return action.get();
        } finally {
            DefaultLogMasker.MASK_EXECUTOR = original;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
