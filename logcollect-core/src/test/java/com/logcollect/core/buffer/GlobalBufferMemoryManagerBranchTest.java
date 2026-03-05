package com.logcollect.core.buffer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalBufferMemoryManagerBranchTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void counterModeFrom_shouldHandleNullBlankInvalidAndValid() {
        assertThat(GlobalBufferMemoryManager.CounterMode.from(null))
                .isEqualTo(GlobalBufferMemoryManager.CounterMode.EXACT_CAS);
        assertThat(GlobalBufferMemoryManager.CounterMode.from("  "))
                .isEqualTo(GlobalBufferMemoryManager.CounterMode.EXACT_CAS);
        assertThat(GlobalBufferMemoryManager.CounterMode.from("unknown"))
                .isEqualTo(GlobalBufferMemoryManager.CounterMode.EXACT_CAS);
        assertThat(GlobalBufferMemoryManager.CounterMode.from("striped_long_adder"))
                .isEqualTo(GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
    }

    @Test
    void constructor_shouldClampToMinimumAndResolveDefaultHardCeiling() {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(512L * 1024L);
        assertThat(manager.getMaxTotalBytes()).isEqualTo(MB);
        assertThat(manager.getHardCeilingBytes()).isEqualTo((long) (MB * 1.5d));

        GlobalBufferMemoryManager disabled = new GlobalBufferMemoryManager(0L);
        assertThat(disabled.getMaxTotalBytes()).isZero();
        assertThat(disabled.getHardCeilingBytes()).isZero();
    }

    @Test
    void constructor_negativeSoftLimit_shouldThrow() {
        assertThatThrownBy(() -> new GlobalBufferMemoryManager(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTotalBytes must be >= 0");
    }

    @Test
    void stripedMode_tryAllocateRejectAndReleaseClamp_shouldWork() {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(
                2 * MB,
                GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER,
                3 * MB);

        assertThat(manager.tryAllocate(1500L * 1024L)).isTrue();
        assertThat(manager.tryAllocate(700L * 1024L)).isFalse();

        manager.release(10 * MB);
        assertThat(manager.getTotalUsed()).isZero();
    }

    @Test
    void forceAllocate_shouldRespectHardCeiling_inExactAndStripedModes() {
        GlobalBufferMemoryManager exact = new GlobalBufferMemoryManager(
                2 * MB,
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                3 * MB);
        assertThat(exact.forceAllocate(2500L * 1024L)).isTrue();
        assertThat(exact.forceAllocate(700L * 1024L)).isFalse();

        GlobalBufferMemoryManager striped = new GlobalBufferMemoryManager(
                2 * MB,
                GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER,
                3 * MB);
        assertThat(striped.forceAllocate(2500L * 1024L)).isTrue();
        assertThat(striped.forceAllocate(700L * 1024L)).isFalse();
    }

    @Test
    void updateLimits_shouldApplyNewSoftAndHardAndSupportDisabledSoftLimit() {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(
                4 * MB,
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                6 * MB);
        assertThat(manager.forceAllocate(5 * MB)).isTrue();

        manager.updateLimits(3 * MB, 4 * MB);
        assertThat(manager.getMaxTotalBytes()).isEqualTo(3 * MB);
        assertThat(manager.getHardCeilingBytes()).isEqualTo(4 * MB);
        assertThat(manager.hardCeilingUtilization()).isGreaterThan(1.0d);

        manager.updateLimits(0L, 0L);
        assertThat(manager.getMaxTotalBytes()).isZero();
        assertThat(manager.getHardCeilingBytes()).isZero();
    }

    @Test
    void forceAllocate_nonPositive_shouldReturnTrue() {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(2 * MB);
        assertThat(manager.forceAllocate(0L)).isTrue();
        assertThat(manager.forceAllocate(-1L)).isTrue();
    }

    @Test
    void formatBytes_privateBranches_shouldBeCovered() throws Exception {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(2 * MB);
        Method formatBytes = GlobalBufferMemoryManager.class.getDeclaredMethod("formatBytes", long.class);
        formatBytes.setAccessible(true);

        assertThat(formatBytes.invoke(manager, -1L)).isEqualTo("unknown");
        assertThat(formatBytes.invoke(manager, 512L)).isEqualTo("512B");
        assertThat(String.valueOf(formatBytes.invoke(manager, 2L * 1024L))).contains("KB");
        assertThat(String.valueOf(formatBytes.invoke(manager, 2L * 1024L * 1024L))).contains("MB");
        assertThat(String.valueOf(formatBytes.invoke(manager, 3L * 1024L * 1024L * 1024L))).contains("GB");
    }
}
