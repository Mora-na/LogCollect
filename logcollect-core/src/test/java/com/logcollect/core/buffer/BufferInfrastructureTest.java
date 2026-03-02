package com.logcollect.core.buffer;

import com.logcollect.api.model.LogEntry;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BufferInfrastructureTest extends CoreUnitTestBase {

    @Test
    void logBuffer_offerDrainShouldFlush_basicFlow() {
        LogBuffer buffer = new LogBuffer(2, parseBytes("1MB"), null);
        LogEntry e1 = createTestEntry("a", "INFO");
        LogEntry e2 = createTestEntry("b", "WARN");

        assertThat(buffer.offer(e1)).isTrue();
        assertThat(buffer.offer(e2)).isTrue();
        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.bytesUsed()).isGreaterThan(0L);
        assertThat(buffer.shouldFlush()).isTrue();

        List<LogEntry> drained = buffer.drain();
        assertThat(drained).hasSize(2);
        assertThat(buffer.size()).isZero();
        assertThat(buffer.bytesUsed()).isZero();
    }

    @Test
    void logBuffer_offerNullAndGlobalReject() {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(0);
        LogBuffer buffer = new LogBuffer(10, parseBytes("1MB"), manager);
        assertThat(buffer.offer(null)).isFalse();
        assertThat(buffer.offer(createTestEntry("payload", "INFO"))).isFalse();
    }

    @Test
    void logCollectBuffer_defaultMethods_workAsContracted() {
        AtomicReference<Boolean> finalFlag = new AtomicReference<Boolean>(Boolean.FALSE);
        LogCollectBuffer buffer = new LogCollectBuffer() {
            @Override
            public boolean offer(com.logcollect.api.model.LogCollectContext context, LogEntry entry) {
                return true;
            }

            @Override
            public void triggerFlush(com.logcollect.api.model.LogCollectContext context, boolean isFinal) {
                finalFlag.set(isFinal);
            }

            @Override
            public void closeAndFlush(com.logcollect.api.model.LogCollectContext context) {
                finalFlag.set(Boolean.TRUE);
            }
        };
        assertThat(buffer.dumpAsString()).isEmpty();
        buffer.forceFlush();
        assertThat(finalFlag.get()).isTrue();
    }

    @Test
    void globalBufferMemoryManager_additionalBranchesCovered() {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(100);
        MetricsStub metrics = new MetricsStub();
        manager.setMetrics(metrics);

        assertThat(manager.tryAllocate(0)).isTrue();
        manager.forceAllocate(150);
        assertThat(manager.getTotalUsed()).isEqualTo(150L);
        assertThat(manager.utilization()).isGreaterThan(1.0d);
        assertThat(metrics.lastUtilization).isEqualTo(1.0d);
        assertThat(manager.getMaxTotalBytes()).isEqualTo(100L);

        manager.release(999);
        assertThat(manager.getTotalUsed()).isZero();
        manager.release(0);
        assertThat(manager.getTotalUsed()).isZero();
    }

    @Test
    void resilientFlusher_successAndFailurePaths() {
        ResilientFlusher flusher = new ResilientFlusher();

        AtomicInteger attempts = new AtomicInteger(0);
        boolean success = flusher.flush(() -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                throw new RuntimeException("fail-" + n);
            }
        }, () -> "snapshot");
        assertThat(success).isTrue();
        assertThat(attempts.get()).isEqualTo(3);

        File fallbackDir = new File(System.getProperty("java.io.tmpdir"), "logcollect-fallback");
        long before = fallbackDir.exists() ? fallbackDir.listFiles((d, name) -> name.startsWith("logcollect-")).length : 0L;

        boolean failed = flusher.flush(() -> {
            throw new RuntimeException("always");
        }, () -> "fallback-data");
        assertThat(failed).isFalse();

        long after = fallbackDir.exists() ? fallbackDir.listFiles((d, name) -> name.startsWith("logcollect-")).length : 0L;
        assertThat(after).isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    void asyncFlushExecutor_nullTask_safe() {
        long before = AsyncFlushExecutor.getRejectedCount();
        AsyncFlushExecutor.submitOrRun(null);
        assertThat(AsyncFlushExecutor.getRejectedCount()).isEqualTo(before);
    }

    @Test
    void asyncFlushExecutor_handleRejected_reflectionCoversBranches() throws Exception {
        Method method = AsyncFlushExecutor.class.getDeclaredMethod(
                "handleRejected", Runnable.class, ThreadPoolExecutor.class, String.class);
        method.setAccessible(true);

        ThreadPoolExecutor withSpace = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(10));
        try {
            AtomicBoolean discarded = new AtomicBoolean(false);
            AtomicInteger downgradedRun = new AtomicInteger(0);
            AsyncFlushExecutor.RejectedAwareTask task = new AsyncFlushExecutor.RejectedAwareTask() {
                @Override
                public Runnable downgradeForRetry() {
                    return downgradedRun::incrementAndGet;
                }

                @Override
                public void onDiscard(String reason) {
                    discarded.set(true);
                }

                @Override
                public void run() {
                }
            };
            method.invoke(null, task, withSpace, "test_reason");
            Runnable queued = withSpace.getQueue().poll();
            assertThat(queued).isNotNull();
            queued.run();
            assertThat(downgradedRun.get()).isEqualTo(1);
            assertThat(discarded.get()).isFalse();
        } finally {
            withSpace.shutdownNow();
        }

        ThreadPoolExecutor fullQueue = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1));
        try {
            fullQueue.getQueue().offer(() -> {
            });
            AtomicBoolean discarded = new AtomicBoolean(false);
            AsyncFlushExecutor.RejectedAwareTask noRetry = new AsyncFlushExecutor.RejectedAwareTask() {
                @Override
                public Runnable downgradeForRetry() {
                    return null;
                }

                @Override
                public void onDiscard(String reason) {
                    discarded.set("queue_full".equals(reason));
                }

                @Override
                public void run() {
                }
            };
            method.invoke(null, noRetry, fullQueue, "queue_full");
            assertThat(discarded.get()).isTrue();
        } finally {
            fullQueue.shutdownNow();
        }

        ThreadPoolExecutor shutdownExecutor = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1));
        shutdownExecutor.shutdown();
        try {
            AtomicBoolean ranAfterShutdown = new AtomicBoolean(false);
            Runnable plain = () -> ranAfterShutdown.set(true);
            method.invoke(null, plain, shutdownExecutor, "shutdown");
            assertThat(ranAfterShutdown.get()).isTrue();
        } finally {
            shutdownExecutor.shutdownNow();
        }

        ThreadPoolExecutor shutdownWithRuntimeException = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1));
        shutdownWithRuntimeException.shutdown();
        try {
            method.invoke(null, (Runnable) () -> {
                throw new IllegalStateException("run-failed");
            }, shutdownWithRuntimeException, "shutdown");
        } finally {
            shutdownWithRuntimeException.shutdownNow();
        }

        ThreadPoolExecutor shutdownWithError = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1));
        shutdownWithError.shutdown();
        try {
            boolean threw = false;
            try {
                method.invoke(null, (Runnable) () -> {
                    throw new AssertionError("fatal");
                }, shutdownWithError, "shutdown");
            } catch (InvocationTargetException ex) {
                threw = true;
                assertThat(ex.getCause()).isInstanceOf(AssertionError.class);
                assertThat(ex.getCause()).hasMessageContaining("fatal");
            }
            assertThat(threw).isTrue();
        } finally {
            shutdownWithError.shutdownNow();
        }
    }

    @Test
    void resilientFlusher_interruptedBranch_preservesInterruptStatus() {
        ResilientFlusher flusher = new ResilientFlusher();
        Thread.currentThread().interrupt();
        try {
            boolean ok = flusher.flush(() -> {
                throw new RuntimeException("always");
            }, () -> "snapshot");
            assertThat(ok).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    static class MetricsStub {
        volatile double lastUtilization = -1.0d;

        @SuppressWarnings("unused")
        public void updateGlobalBufferUtilization(double utilization) {
            this.lastUtilization = utilization;
        }
    }
}
