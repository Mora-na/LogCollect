package com.logcollect.core.buffer;

import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncFlushExecutorAdditionalTest extends CoreUnitTestBase {

    @Test
    void submitOrRun_successPath_executesTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AsyncFlushExecutor.submitOrRun(latch::countDown);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectedHandler_invocation_triggersDiscardReason() throws Exception {
        Class<?> handlerClass = Class.forName("com.logcollect.core.buffer.AsyncFlushExecutor$LogCollectRejectedHandler");
        Constructor<?> constructor = handlerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object rejectedHandler = constructor.newInstance();
        Method rejectedExecution = handlerClass.getDeclaredMethod(
                "rejectedExecution", Runnable.class, ThreadPoolExecutor.class);
        rejectedExecution.setAccessible(true);

        ThreadPoolExecutor fullQueueExecutor = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1));
        try {
            fullQueueExecutor.getQueue().offer(() -> {
            });
            AtomicBoolean discarded = new AtomicBoolean(false);
            AsyncFlushExecutor.RejectedAwareTask task = new AsyncFlushExecutor.RejectedAwareTask() {
                @Override
                public Runnable downgradeForRetry() {
                    return null;
                }

                @Override
                public void onDiscard(String reason) {
                    discarded.set("async_queue_full".equals(reason));
                }

                @Override
                public void run() {
                }
            };

            rejectedExecution.invoke(rejectedHandler, task, fullQueueExecutor);
            assertThat(discarded.get()).isTrue();
        } finally {
            fullQueueExecutor.shutdownNow();
        }
    }
}
