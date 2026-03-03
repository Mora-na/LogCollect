package com.logcollect.core.buffer;

import com.logcollect.core.test.ConcurrentTestHelper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GlobalBufferMemoryManagerTest {

    @Test
    void tryAllocate_withinLimit_succeeds() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(1024);
        assertThat(mgr.tryAllocate(512)).isTrue();
        assertThat(mgr.getTotalUsed()).isEqualTo(512);
    }

    @Test
    void tryAllocate_exceedsLimit_fails() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(1024);
        assertThat(mgr.tryAllocate(512)).isTrue();
        assertThat(mgr.tryAllocate(513)).isFalse();
    }

    @Test
    void release_decreasesUsed() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(1024);
        mgr.tryAllocate(512);
        mgr.release(256);
        assertThat(mgr.getTotalUsed()).isEqualTo(256);
    }

    @Test
    void utilization_correctRatio() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(1000);
        mgr.tryAllocate(500);
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
                1024, GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
        assertThat(mgr.getCounterMode()).isEqualTo(GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
        assertThat(mgr.tryAllocate(512)).isTrue();
        assertThat(mgr.tryAllocate(600)).isFalse();
        mgr.release(128);
        assertThat(mgr.getTotalUsed()).isEqualTo(384);
    }

    @Test
    void stripedLongAdderMode_utilizationReflectsStripedCounter() {
        GlobalBufferMemoryManager mgr = new GlobalBufferMemoryManager(
                1000, GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
        assertThat(mgr.tryAllocate(400)).isTrue();
        assertThat(mgr.utilization()).isCloseTo(0.4d, offset(0.01d));
    }
}
