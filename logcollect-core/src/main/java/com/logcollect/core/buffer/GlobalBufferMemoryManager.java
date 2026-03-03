package com.logcollect.core.buffer;

import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 全局缓冲内存配额管理器。
 *
 * <p>用于限制多个方法缓冲区累计内存占用，并通过 {@link LogCollectMetrics} 直连更新外部指标。
 */
public class GlobalBufferMemoryManager {
    public enum CounterMode {
        EXACT_CAS,
        STRIPED_LONG_ADDER;

        public static CounterMode from(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return EXACT_CAS;
            }
            try {
                return CounterMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return EXACT_CAS;
            }
        }
    }

    private final AtomicLong totalUsed = new AtomicLong(0);
    private final LongAdder stripedTotalUsed = new LongAdder();
    private final Object stripedClampLock = new Object();
    private final long maxTotalBytes;
    private final CounterMode counterMode;
    private volatile LogCollectMetrics metrics = NoopLogCollectMetrics.INSTANCE;

    /**
     * 创建全局配额管理器。
     *
     * @param maxTotalBytes 全局最大字节预算
     */
    public GlobalBufferMemoryManager(long maxTotalBytes) {
        this(maxTotalBytes, CounterMode.EXACT_CAS);
    }

    /**
     * 创建全局配额管理器。
     *
     * @param maxTotalBytes 全局最大字节预算
     * @param counterMode   计数模式（精确 CAS / 分段 LongAdder）
     */
    public GlobalBufferMemoryManager(long maxTotalBytes, CounterMode counterMode) {
        this.maxTotalBytes = maxTotalBytes;
        this.counterMode = counterMode == null ? CounterMode.EXACT_CAS : counterMode;
    }

    /**
     * 尝试申请内存配额。
     *
     * @param bytes 申请字节数
     * @return true 表示申请成功
     */
    public boolean tryAllocate(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            stripedTotalUsed.add(bytes);
            if (stripedTotalUsed.sum() > maxTotalBytes) {
                stripedTotalUsed.add(-bytes);
                updateMetrics();
                return false;
            }
            updateMetrics();
            return true;
        }
        while (true) {
            long current = totalUsed.get();
            if (current + bytes > maxTotalBytes) {
                updateMetrics();
                return false;
            }
            if (totalUsed.compareAndSet(current, current + bytes)) {
                updateMetrics();
                return true;
            }
        }
    }

    /**
     * 强制申请内存配额（可突破上限）。
     *
     * @param bytes 申请字节数
     */
    public void forceAllocate(long bytes) {
        if (bytes <= 0) {
            return;
        }
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            stripedTotalUsed.add(bytes);
        } else {
            totalUsed.addAndGet(bytes);
        }
        updateMetrics();
    }

    /**
     * 释放已占用配额。
     *
     * @param bytes 释放字节数
     */
    public void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            stripedTotalUsed.add(-bytes);
            long remaining = stripedTotalUsed.sum();
            if (remaining < 0) {
                synchronized (stripedClampLock) {
                    long current = stripedTotalUsed.sum();
                    if (current < 0) {
                        stripedTotalUsed.add(-current);
                    }
                }
            }
        } else {
            long remaining = totalUsed.addAndGet(-bytes);
            if (remaining < 0) {
                totalUsed.set(0);
            }
        }
        updateMetrics();
    }

    /**
     * 返回当前全局利用率。
     *
     * @return 使用率（0~1，若上限为 0 则返回 0）
     */
    public double utilization() {
        long used = getTotalUsed();
        return maxTotalBytes == 0 ? 0.0 : (double) used / (double) maxTotalBytes;
    }

    /**
     * 获取当前已占用字节数。
     *
     * @return 已占用字节数
     */
    public long getTotalUsed() {
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            long used = stripedTotalUsed.sum();
            return used < 0 ? 0 : used;
        }
        return totalUsed.get();
    }

    /**
     * 获取全局最大字节预算。
     *
     * @return 最大字节预算
     */
    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public CounterMode getCounterMode() {
        return counterMode;
    }

    /**
     * 设置指标桥接对象。
     *
     * @param metrics 指标对象，可为 null
     */
    public void setMetrics(LogCollectMetrics metrics) {
        this.metrics = metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
        updateMetrics();
    }

    private void updateMetrics() {
        double utilization = utilization();
        if (utilization < 0.0d) {
            utilization = 0.0d;
        } else if (utilization > 1.0d) {
            utilization = 1.0d;
        }
        metrics.updateGlobalBufferUtilization(utilization);
    }
}
