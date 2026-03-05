package com.logcollect.core.buffer;

import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 全局缓冲内存配额管理器。
 *
 * <p>soft limit（软限）用于常规配额，hard ceiling（硬顶）用于强制分配上限。</p>
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

    private final AtomicLong usedBytesCas;
    private final LongAdder usedBytesAdder;
    private final AtomicLong forceAllocateUsed;

    private final CounterMode counterMode;
    private volatile LogCollectMetrics metrics = NoopLogCollectMetrics.INSTANCE;

    public GlobalBufferMemoryManager(long maxTotalBytes) {
        this(maxTotalBytes, CounterMode.EXACT_CAS, 0L);
    }

    public GlobalBufferMemoryManager(long maxTotalBytes, CounterMode counterMode) {
        this(maxTotalBytes, counterMode, 0L);
    }

    public GlobalBufferMemoryManager(long maxTotalBytes, CounterMode counterMode, long hardCeilingBytes) {
        this.counterMode = counterMode == null ? CounterMode.EXACT_CAS : counterMode;
        long validatedSoft = validateMaxBytes(maxTotalBytes);
        this.maxTotalBytes = new AtomicLong(validatedSoft);

        long ceiling = resolveHardCeilingBytes(validatedSoft, hardCeilingBytes);
        this.hardCeilingBytes = new AtomicLong(ceiling);

        this.usedBytesCas = new AtomicLong(0L);
        this.usedBytesAdder = this.counterMode == CounterMode.STRIPED_LONG_ADDER ? new LongAdder() : null;
        this.forceAllocateUsed = new AtomicLong(0L);
        updateMetrics();
    }

    public boolean tryAllocate(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        long softLimit = maxTotalBytes.get();
        if (softLimit <= 0) {
            return true;
        }

        if (counterMode == CounterMode.EXACT_CAS) {
            while (true) {
                long current = usedBytesCas.get();
                long next = current + bytes;
                if (next > softLimit) {
                    updateMetrics();
                    return false;
                }
                if (usedBytesCas.compareAndSet(current, next)) {
                    updateMetrics();
                    return true;
                }
            }
        }

        long preCheck = usedBytesAdder.sum() + forceAllocateUsed.get() + bytes;
        if (preCheck > softLimit) {
            updateMetrics();
            return false;
        }

        usedBytesAdder.add(bytes);
        long postCheck = usedBytesAdder.sum() + forceAllocateUsed.get();
        if (postCheck > softLimit) {
            usedBytesAdder.add(-bytes);
            updateMetrics();
            return false;
        }
        updateMetrics();
        return true;
    }

    /**
     * 高等级日志强制分配。
     *
     * <p>无论 counterMode 是什么，forceAllocate 始终走 CAS 精确路径，消除 STRIPED 瞬时超射。</p>
     */
    public boolean forceAllocate(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        long ceiling = hardCeilingBytes.get();
        if (ceiling <= 0) {
            doAdd(bytes);
            updateMetrics();
            return true;
        }

        while (true) {
            long currentForce = forceAllocateUsed.get();
            long currentTotal = getCurrentUsedBytes();
            long nextTotal = currentTotal + bytes;
            if (nextTotal > ceiling) {
                updateMetrics();
                return false;
            }
            if (forceAllocateUsed.compareAndSet(currentForce, currentForce + bytes)) {
                doAdd(bytes);
                forceAllocateUsed.addAndGet(-bytes);
                updateMetrics();
                return true;
            }
        }
    }

    public void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        if (counterMode == CounterMode.EXACT_CAS) {
            long left = usedBytesCas.addAndGet(-bytes);
            if (left < 0) {
                usedBytesCas.set(0L);
            }
        } else {
            usedBytesAdder.add(-bytes);
            long left = usedBytesAdder.sum();
            if (left < 0) {
                usedBytesAdder.add(-left);
            }
        }
        updateMetrics();
    }

    public void updateLimits(long newSoftBytes, long newHardBytes) {
        long validatedSoft = validateMaxBytes(newSoftBytes);
        long oldSoft = this.maxTotalBytes.getAndSet(validatedSoft);

        long ceiling = resolveHardCeilingBytes(validatedSoft, newHardBytes);
        long oldHard = this.hardCeilingBytes.getAndSet(ceiling);

        long currentUsed = getCurrentUsedBytes();
        LogCollectInternalLogger.info(
                "Global buffer limits updated: soft {} -> {}, hard {} -> {}, used={} ",
                formatBytes(oldSoft), formatBytes(validatedSoft),
                formatBytes(oldHard), formatBytes(ceiling),
                formatBytes(currentUsed));
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
        long base = counterMode == CounterMode.EXACT_CAS
                ? usedBytesCas.get()
                : usedBytesAdder.sum();
        long result = base + forceAllocateUsed.get();
        return Math.max(0L, result);
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

    private void doAdd(long bytes) {
        if (counterMode == CounterMode.EXACT_CAS) {
            usedBytesCas.addAndGet(bytes);
        } else {
            usedBytesAdder.add(bytes);
        }
    }

    private void updateMetrics() {
        LogCollectMetrics current = metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
        current.updateGlobalBufferUtilization(utilization());
        current.updateGlobalHardCeilingUtilization(hardCeilingUtilization());
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

    private long resolveHardCeilingBytes(long soft, long explicitHard) {
        if (explicitHard > 0) {
            return explicitHard;
        }
        if (soft <= 0) {
            return 0L;
        }
        return (long) (soft * DEFAULT_HARD_CEILING_RATIO);
    }

    private String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "unknown";
        }
        if (bytes < 1024L) {
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
