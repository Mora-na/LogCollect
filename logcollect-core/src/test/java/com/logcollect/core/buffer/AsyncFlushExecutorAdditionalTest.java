package com.logcollect.core.buffer;

import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncFlushExecutorAdditionalTest extends CoreUnitTestBase {

    @Test
    void submitOrRun_successPath_executesTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AsyncFlushExecutor.submitOrRun(latch::countDown);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void callerRunsPolicy_executesOnSubmitterWhenSaturated() throws Exception {
        try {
            AsyncFlushExecutor.configure(1, 1, 1);
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<String> thirdThread = new AtomicReference<String>();

            AsyncFlushExecutor.submitOrRun(() -> {
                try {
                    blocker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            AsyncFlushExecutor.submitOrRun(done::countDown);
            String submitter = Thread.currentThread().getName();
            AsyncFlushExecutor.submitOrRun(() -> thirdThread.set(Thread.currentThread().getName()));

            assertThat(thirdThread.get()).isEqualTo(submitter);
            blocker.countDown();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            AsyncFlushExecutor.configure(2, 4, 4096);
        }
    }
}
