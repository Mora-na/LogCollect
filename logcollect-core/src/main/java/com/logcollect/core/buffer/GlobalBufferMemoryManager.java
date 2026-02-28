package com.logcollect.core.buffer;

import java.util.concurrent.atomic.AtomicLong;

public class GlobalBufferMemoryManager {
    private final AtomicLong totalUsed = new AtomicLong(0);
    private final long maxTotalBytes;

    public GlobalBufferMemoryManager(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public boolean tryAllocate(long bytes) {
        if (bytes <= 0) {
            return true;
        }
        while (true) {
            long current = totalUsed.get();
            if (current + bytes > maxTotalBytes) {
                return false;
            }
            if (totalUsed.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public void forceAllocate(long bytes) {
        if (bytes <= 0) {
            return;
        }
        totalUsed.addAndGet(bytes);
    }

    public void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        totalUsed.addAndGet(-bytes);
    }

    public double utilization() {
        return maxTotalBytes == 0 ? 0.0 : (double) totalUsed.get() / (double) maxTotalBytes;
    }

    public long getTotalUsed() {
        return totalUsed.get();
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }
}
