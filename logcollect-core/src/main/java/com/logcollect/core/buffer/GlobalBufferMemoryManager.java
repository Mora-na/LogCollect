package com.logcollect.core.buffer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局缓冲内存配额管理器。
 *
 * <p>用于限制多个方法缓冲区累计内存占用，并可通过反射回调更新外部指标。
 */
public class GlobalBufferMemoryManager {
    private final AtomicLong totalUsed = new AtomicLong(0);
    private final long maxTotalBytes;
    private volatile Object metrics;

    /**
     * 创建全局配额管理器。
     *
     * @param maxTotalBytes 全局最大字节预算
     */
    public GlobalBufferMemoryManager(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
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
        totalUsed.addAndGet(bytes);
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
        long remaining = totalUsed.addAndGet(-bytes);
        if (remaining < 0) {
            totalUsed.set(0);
        }
        updateMetrics();
    }

    /**
     * 返回当前全局利用率。
     *
     * @return 使用率（0~1，若上限为 0 则返回 0）
     */
    public double utilization() {
        return maxTotalBytes == 0 ? 0.0 : (double) totalUsed.get() / (double) maxTotalBytes;
    }

    /**
     * 获取当前已占用字节数。
     *
     * @return 已占用字节数
     */
    public long getTotalUsed() {
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

    /**
     * 设置指标桥接对象。
     *
     * <p>若对象包含 {@code updateGlobalBufferUtilization(double)} 方法，会在配额变化时被调用。
     *
     * @param metrics 指标对象，可为 null
     */
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
