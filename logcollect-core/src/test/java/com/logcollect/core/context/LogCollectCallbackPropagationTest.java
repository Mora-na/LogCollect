package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectCallbackPropagationTest extends CoreUnitTestBase {

    @Test
    void whenComplete_withoutWrapper_onForeignCompletionThread_shouldNotPropagateContext() throws Exception {
        LogCollectContext context = createTestContext();
        String traceId = context.getTraceId();
        CompletableFuture<String> future = new CompletableFuture<String>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(context);
        try {
            future.whenComplete((value, error) -> {
                LogCollectContext current = LogCollectContextManager.current();
                callbackTraceId.set(current == null ? null : current.getTraceId());
                latch.countDown();
            });
        } finally {
            LogCollectContextManager.clear();
        }

        Thread completionThread = new Thread(() -> future.complete(traceId), "cf-raw-callback");
        completionThread.start();
        completionThread.join(3000);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackTraceId.get()).isNull();
    }

    @Test
    void wrapBiConsumer_withWhenComplete_onForeignCompletionThread_shouldPropagateContext() throws Exception {
        LogCollectContext context = createTestContext();
        String traceId = context.getTraceId();
        CompletableFuture<String> future = new CompletableFuture<String>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(context);
        try {
            future.whenComplete(LogCollectContextUtils.wrapBiConsumer((value, error) -> {
                LogCollectContext current = LogCollectContextManager.current();
                callbackTraceId.set(current == null ? null : current.getTraceId());
                latch.countDown();
            }));
        } finally {
            LogCollectContextManager.clear();
        }

        Thread completionThread = new Thread(() -> future.complete(traceId), "cf-wrapped-callback");
        completionThread.start();
        completionThread.join(3000);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackTraceId.get()).isEqualTo(traceId);
    }
}
