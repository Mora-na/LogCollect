package com.logcollect.autoconfigure.async;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectSpringCallbackUtilsTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void addCallback_withoutWrapper_onForeignCompletionThread_shouldNotPropagateContext() throws Exception {
        SettableListenableFuture<String> future = new SettableListenableFuture<String>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(newContext("trace-raw-listenable"));
        try {
            future.addCallback(new ListenableFutureCallback<String>() {
                @Override
                public void onFailure(Throwable ex) {
                    latch.countDown();
                }

                @Override
                public void onSuccess(String result) {
                    LogCollectContext current = LogCollectContextManager.current();
                    callbackTraceId.set(current == null ? null : current.getTraceId());
                    latch.countDown();
                }
            });
        } finally {
            LogCollectContextManager.clear();
        }

        Thread completionThread = new Thread(() -> future.set("ok"), "lf-raw-callback");
        completionThread.start();
        completionThread.join(3000);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackTraceId.get()).isNull();
    }

    @Test
    void wrapListenableFutureCallback_onForeignCompletionThread_shouldPropagateContext() throws Exception {
        String traceId = "trace-wrapped-listenable";
        SettableListenableFuture<String> future = new SettableListenableFuture<String>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(newContext(traceId));
        try {
            future.addCallback(LogCollectSpringCallbackUtils.wrapListenableFutureCallback(new ListenableFutureCallback<String>() {
                @Override
                public void onFailure(Throwable ex) {
                    latch.countDown();
                }

                @Override
                public void onSuccess(String result) {
                    LogCollectContext current = LogCollectContextManager.current();
                    callbackTraceId.set(current == null ? null : current.getTraceId());
                    latch.countDown();
                }
            }));
        } finally {
            LogCollectContextManager.clear();
        }

        Thread completionThread = new Thread(() -> future.set("ok"), "lf-wrapped-callback");
        completionThread.start();
        completionThread.join(3000);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackTraceId.get()).isEqualTo(traceId);
    }

    @Test
    void wrapSuccessAndFailureCallback_onForeignCompletionThread_shouldPropagateContext() throws Exception {
        String traceId = "trace-wrapped-listenable-failure";
        SettableListenableFuture<String> future = new SettableListenableFuture<String>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(newContext(traceId));
        try {
            future.addCallback(
                    LogCollectSpringCallbackUtils.wrapSuccessCallback(result -> callbackTraceId.set("unexpected-success")),
                    LogCollectSpringCallbackUtils.wrapFailureCallback(ex -> {
                        LogCollectContext current = LogCollectContextManager.current();
                        callbackTraceId.set(current == null ? null : current.getTraceId());
                        latch.countDown();
                    }));
        } finally {
            LogCollectContextManager.clear();
        }

        Thread completionThread = new Thread(() -> future.setException(new IllegalStateException("boom")),
                "lf-wrapped-failure-callback");
        completionThread.start();
        completionThread.join(3000);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackTraceId.get()).isEqualTo(traceId);
    }

    @Test
    void nullSafety_shouldReturnNull() {
        assertThat(LogCollectSpringCallbackUtils.wrapListenableFutureCallback(null)).isNull();
        assertThat(LogCollectSpringCallbackUtils.wrapSuccessCallback(null)).isNull();
        assertThat(LogCollectSpringCallbackUtils.wrapFailureCallback(null)).isNull();
    }

    private static LogCollectContext newContext(String traceId) {
        return new LogCollectContext(traceId, null, null, null, null, null, null, null);
    }
}
