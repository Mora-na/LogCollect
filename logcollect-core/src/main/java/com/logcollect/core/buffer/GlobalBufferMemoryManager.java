package com.logcollect.core.buffer;

import java.util.concurrent.atomic.AtomicLong;

public class GlobalBufferMemoryManager {
    private final AtomicLong totalUsed = new AtomicLong(0);
    private final long maxTotalBytes;
    private volatile Object metrics;

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
                updateMetrics();
                return false;
            }
            if (totalUsed.compareAndSet(current, current + bytes)) {
                updateMetrics();
                return true;
            }
        }
    }

    public void forceAllocate(long bytes) {
        if (bytes <= 0) {
            return;
        }
        totalUsed.addAndGet(bytes);
        updateMetrics();
    }

    public void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        long remaining = totalUsed.addAndGet(-bytes);
        if (remaining < 0) {
            totalUsed.set(0);
        }
        updateMetrics();
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

    public void setMetrics(Object metrics) {
        this.metrics = metrics;
        updateMetrics();
    }

    private void updateMetrics() {
        Object target = metrics;
        if (target == null) {
            return;
        }
        double utilization = utilization();
        if (utilization < 0.0d) {
            utilization = 0.0d;
        } else if (utilization > 1.0d) {
            utilization = 1.0d;
        }
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
                if (!"updateGlobalBufferUtilization".equals(method.getName())
                        || method.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> type = method.getParameterTypes()[0];
                if (type != double.class && type != Double.class) {
                    continue;
                }
                method.invoke(target, utilization);
                break MethodLoop;
            }
        } catch (Throwable ignored) {
        }
    }
}
