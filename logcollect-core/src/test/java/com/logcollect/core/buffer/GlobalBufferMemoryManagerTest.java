package com.logcollect.core.buffer;

import com.logcollect.core.test.ConcurrentTestHelper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GlobalBufferMemoryManagerTest {
    private static final long MB = 1024L * 1024L;

    @Test
    void tryAllocate_withinLimit_succeeds() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(2 * MB);
        assertThat(mgr.tryAllocate(1 * MB)).isTrue();
        assertThat(mgr.getTotalUsed()).isEqualTo(1 * MB);
    }

    @Test
    void tryAllocate_exceedsLimit_fails() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(2 * MB);
        assertThat(mgr.tryAllocate(1 * MB)).isTrue();
        assertThat(mgr.tryAllocate(1 * MB + 1)).isFalse();
    }

    @Test
    void release_decreasesUsed() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(2 * MB);
        mgr.tryAllocate(1 * MB);
        mgr.release(512 * 1024L);
        assertThat(mgr.getTotalUsed()).isEqualTo(512 * 1024L);
    }

    @Test
    void utilization_correctRatio() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(2 * MB);
        mgr.tryAllocate(1 * MB);
        assertThat(mgr.utilization()).isCloseTo(0.5d, offset(0.01d));
    }

    @Test
    void concurrentAllocateAndRelease_neverNegative() throws Exception {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(10 * 1024 * 1024);
        ConcurrentTestHelper.runConcurrently(20, () -> {
            for (int i = 0; i < 1000; i++) {
                if (mgr.tryAllocate(100)) {
                    mgr.release(100);
                }
            }
        }, Duration.ofSeconds(10));
        assertThat(mgr.getTotalUsed()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void stripedLongAdderMode_worksWithLimitAndRelease() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(
                2 * MB, GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
        assertThat(mgr.getCounterMode()).isEqualTo(GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
        assertThat(mgr.tryAllocate(1 * MB)).isTrue();
        assertThat(mgr.tryAllocate(2 * MB)).isFalse();
        mgr.release(512 * 1024L);
        assertThat(mgr.getTotalUsed()).isEqualTo(512 * 1024L);
    }

    @Test
    void stripedLongAdderMode_utilizationReflectsStripedCounter() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(
                2 * MB, GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
        assertThat(mgr.tryAllocate((long) (0.8 * MB))).isTrue();
        assertThat(mgr.utilization()).isCloseTo(0.4d, offset(0.01d));
    }
}
