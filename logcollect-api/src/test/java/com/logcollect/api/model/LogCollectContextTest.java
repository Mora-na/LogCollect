package com.logcollect.api.model;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectContextTest {

    @Test
    void threadSafety_concurrentAttributeAccess() throws Exception {
        LogCollectContext ctx = newContext();
        int threadCount = 10;
        int loop = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int j = 0; j < loop; j++) {
                            String key = "key-" + Thread.currentThread().getId();
                            ctx.setAttribute(key, j);
                            ctx.getAttribute(key, Integer.class);
                            ctx.incrementCollectedCount();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            done.await(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ctx.getTotalCollectedCount()).isEqualTo(threadCount * loop);
    }

    @Test
    void methodArgs_returnsCopy() {
        LogCollectContext ctx = newContext();
        Object[] args = ctx.getMethodArgs();
        if (args.length > 0) {
            args[0] = "changed";
        }
        assertThat(ctx.getMethodArgs()).isNotSameAs(args);
    }

    private LogCollectContext newContext() {
        try {
            Method method = LogCollectContextTest.class.getDeclaredMethod("marker", String.class);
            return new LogCollectContext(
                    "t",
                    method,
                    new Object[]{"arg"},
                    LogCollectConfig.frameworkDefaults(),
                    new LogCollectHandler() {
                    },
                    null,
                    null,
                    CollectMode.AGGREGATE
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void marker(String value) {
        LocalDateTime.now();
    }
}
