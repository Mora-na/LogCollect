package com.logcollect.core.buffer;

import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 全局缓冲内存配额管理器。
 *
 * <p>soft limit（软限）用于常规配额约束，hard ceiling（硬顶）用于高等级强制分配上限。
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

    private static final long MIN_TOTAL_BYTES = 1024L * 1024L;
    private static final double DEFAULT_HARD_CEILING_RATIO = 1.5d;

    private final AtomicLong maxTotalBytes;
    private final AtomicLong hardCeilingBytes;
    private final CounterMode counterMode;

    private final AtomicLong totalUsed = new AtomicLong(0L);
    private final LongAdder stripedTotalUsed;
    private final Object stripedClampLock = new Object();

    private volatile LogCollectMetrics metrics = NoopLogCollectMetrics.INSTANCE;

    public GlobalBufferMemoryManager(long maxTotalBytes) {
        this(maxTotalBytes, CounterMode.EXACT_CAS, 0L);
    }

    public GlobalBufferMemoryManager(long maxTotalBytes, CounterMode counterMode) {
        this(maxTotalBytes, counterMode, 0L);
    }

    public GlobalBufferMemoryManager(long maxTotalBytes, CounterMode counterMode, long hardCeilingBytes) {
        this.counterMode = counterMode == null ? CounterMode.EXACT_CAS : counterMode;
        long soft = validateMaxBytes(maxTotalBytes);
        this.maxTotalBytes = new AtomicLong(soft);
        long hard = resolveHardCeilingBytes(soft, hardCeilingBytes);
        this.hardCeilingBytes = new AtomicLong(hard);
        this.stripedTotalUsed = this.counterMode == CounterMode.STRIPED_LONG_ADDER ? new LongAdder() : null;
        updateMetrics();
    }

    public boolean tryAllocate(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        long soft = maxTotalBytes.get();
        if (soft <= 0) {
            return true;
        }
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            long estimated = stripedTotalUsed.sum() + bytes;
            if (estimated > soft) {
                updateMetrics();
                return false;
            }
            stripedTotalUsed.add(bytes);
            if (stripedTotalUsed.sum() > soft) {
                stripedTotalUsed.add(-bytes);
                updateMetrics();
                return false;
            }
            updateMetrics();
            return true;
        }
        while (true) {
            long current = totalUsed.get();
            long next = current + bytes;
            if (next > soft) {
                updateMetrics();
                return false;
            }
            if (totalUsed.compareAndSet(current, next)) {
                updateMetrics();
                return true;
            }
        }
    }

    /**
     * 高等级日志强制分配。
     *
     * <p>可突破 soft limit，但不能突破 hard ceiling。
     *
     * @return true 分配成功；false 表示达到硬顶
     */
    public boolean forceAllocate(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        long hard = hardCeilingBytes.get();
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            if (hard > 0) {
                long estimated = stripedTotalUsed.sum() + bytes;
                if (estimated > hard) {
                    updateMetrics();
                    return false;
                }
            }
            stripedTotalUsed.add(bytes);
            if (hard > 0 && stripedTotalUsed.sum() > hard) {
                stripedTotalUsed.add(-bytes);
                updateMetrics();
                return false;
            }
            updateMetrics();
            return true;
        }
        while (true) {
            long current = totalUsed.get();
            long next = current + bytes;
            if (hard > 0 && next > hard) {
                updateMetrics();
                return false;
            }
            if (totalUsed.compareAndSet(current, next)) {
                updateMetrics();
                return true;
            }
        }
    }

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
     * 动态更新软限与硬顶。
     */
    public void updateLimits(long newMaxTotalBytes, long newHardCeilingBytes) {
        long validatedSoft = validateMaxBytes(newMaxTotalBytes);
        long oldSoft = this.maxTotalBytes.getAndSet(validatedSoft);
        long oldHard = this.hardCeilingBytes.get();
        long resolvedHard = resolveHardCeilingBytes(validatedSoft, newHardCeilingBytes);
        this.hardCeilingBytes.set(resolvedHard);

        long used = getCurrentUsedBytes();
        LogCollectInternalLogger.info(
                "Global buffer limits updated: soft {} -> {}, hard {} -> {}, used={}",
                formatBytes(oldSoft), formatBytes(validatedSoft),
                formatBytes(oldHard), formatBytes(resolvedHard),
                formatBytes(used));
        if (validatedSoft > 0 && used > validatedSoft) {
            LogCollectInternalLogger.warn(
                    "New soft limit ({}) < current usage ({}). New allocations will be rejected until usage drops.",
                    formatBytes(validatedSoft), formatBytes(used));
        }
        updateMetrics();
    }

    public double utilization() {
        long soft = maxTotalBytes.get();
        if (soft <= 0) {
            return 0.0d;
        }
        return (double) getCurrentUsedBytes() / (double) soft;
    }

    public double hardCeilingUtilization() {
        long hard = hardCeilingBytes.get();
        if (hard <= 0) {
            return 0.0d;
        }
        return (double) getCurrentUsedBytes() / (double) hard;
    }

    public long getCurrentUsedBytes() {
        if (counterMode == CounterMode.STRIPED_LONG_ADDER) {
            long used = stripedTotalUsed.sum();
            return Math.max(0L, used);
        }
        return Math.max(0L, totalUsed.get());
    }

    public long getTotalUsed() {
        return getCurrentUsedBytes();
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes.get();
    }

    public long getHardCeilingBytes() {
        return hardCeilingBytes.get();
    }

    public CounterMode getCounterMode() {
        return counterMode;
    }

    public void setMetrics(LogCollectMetrics metrics) {
        this.metrics = metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
        updateMetrics();
    }

    private void updateMetrics() {
        LogCollectMetrics m = metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
        m.updateGlobalBufferUtilization(utilization());
        m.updateGlobalHardCeilingUtilization(hardCeilingUtilization());
    }

    private long resolveHardCeilingBytes(long soft, long explicitHard) {
        if (explicitHard > 0) {
            return explicitHard;
        }
        if (soft <= 0) {
            return 0L;
        }
        return (long) (soft * DEFAULT_HARD_CEILING_RATIO);
    }

    private long validateMaxBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("maxTotalBytes must be >= 0, got: " + bytes);
        }
        if (bytes > 0 && bytes < MIN_TOTAL_BYTES) {
            LogCollectInternalLogger.warn("maxTotalBytes {} below minimum {}, using minimum",
                    formatBytes(bytes), formatBytes(MIN_TOTAL_BYTES));
            return MIN_TOTAL_BYTES;
        }
        return bytes;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1fKB", bytes / 1024.0d);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.1fMB", bytes / (1024.0d * 1024.0d));
        }
        return String.format("%.2fGB", bytes / (1024.0d * 1024.0d * 1024.0d));
    }
}
