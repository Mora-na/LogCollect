package com.logcollect.core.buffer;

import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
        assertThat(buffer.offer(createTestEntry("payload", "INFO"))).isTrue();
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
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(parseBytes("1MB"));
        MetricsStub metrics = new MetricsStub();
        manager.setMetrics(metrics);

        assertThat(manager.tryAllocate(0)).isTrue();
        assertThat(manager.forceAllocate(parseBytes("1200KB"))).isTrue();
        assertThat(manager.getTotalUsed()).isEqualTo(parseBytes("1200KB"));
        assertThat(manager.utilization()).isGreaterThan(1.0d);
        assertThat(metrics.lastUtilization).isGreaterThan(1.0d);
        assertThat(manager.getMaxTotalBytes()).isEqualTo(parseBytes("1MB"));

        manager.release(parseBytes("2MB"));
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
    void asyncFlushExecutor_callerRunsWhenPoolSaturated() throws Exception {
        try {
            AsyncFlushExecutor.configure(1, 1, 1);
            CountDownLatch block = new CountDownLatch(1);
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(2);
            AtomicReference<String> callerThread = new AtomicReference<String>();

            AsyncFlushExecutor.submitOrRun(() -> {
                workerStarted.countDown();
                try {
                    block.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            AsyncFlushExecutor.submitOrRun(finished::countDown);

            String testThread = Thread.currentThread().getName();
            AsyncFlushExecutor.submitOrRun(() -> callerThread.set(Thread.currentThread().getName()));

            assertThat(callerThread.get()).isEqualTo(testThread);
            block.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            AsyncFlushExecutor.configure(2, 4, 4096);
        }
    }

    @Test
    void asyncFlushExecutor_submitAfterShutdown_runsInlineFallback() {
        try {
            AsyncFlushExecutor.configure(1, 1, 8);
            AsyncFlushExecutor.shutdownAndAwait(100);
            AtomicBoolean ran = new AtomicBoolean(false);
            AsyncFlushExecutor.submitOrRun(() -> ran.set(true));
            assertThat(ran.get()).isTrue();
        } finally {
            AsyncFlushExecutor.configure(2, 4, 4096);
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

    static class MetricsStub implements LogCollectMetrics {
        volatile double lastUtilization = -1.0d;

        @Override
        public void updateGlobalBufferUtilization(double utilization) {
            this.lastUtilization = utilization;
        }

        @Override
        public void incrementDiscarded(String methodKey, String reason) {
        }

        @Override
        public void incrementCollected(String methodKey, String level, String mode) {
        }

        @Override
        public void incrementPersisted(String methodKey, String mode) {
        }

        @Override
        public void incrementPersistFailed(String methodKey) {
        }

        @Override
        public void incrementFlush(String methodKey, String mode, String trigger) {
        }

        @Override
        public void incrementBufferOverflow(String methodKey, String overflowPolicy) {
        }

        @Override
        public void incrementDegradeTriggered(String type, String methodKey) {
        }

        @Override
        public void incrementCircuitRecovered(String methodKey) {
        }

        @Override
        public void incrementSanitizeHits(String methodKey) {
        }

        @Override
        public void incrementMaskHits(String methodKey) {
        }

        @Override
        public void incrementHandlerTimeout(String methodKey) {
        }

        @Override
        public void updateBufferUtilization(String methodKey, double utilization) {
        }

        @Override
        public Object startSecurityTimer() {
            return null;
        }

        @Override
        public void stopSecurityTimer(Object timerSample, String methodKey) {
        }

        @Override
        public Object startPersistTimer() {
            return null;
        }

        @Override
        public void stopPersistTimer(Object timerSample, String methodKey, String mode) {
        }
    }
}
