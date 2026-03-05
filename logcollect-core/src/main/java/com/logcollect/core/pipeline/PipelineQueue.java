package com.logcollect.core.pipeline;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 业务线程与 Consumer 线程之间的有界传输队列。
 *
 * @deprecated v2.1 起由 {@link PipelineRingBuffer} 取代，仅保留兼容。
 */
@Deprecated
public final class PipelineQueue {

    public enum OfferResult {
        ACCEPTED,
        BACKPRESSURE_REJECTED,
        FULL
    }

    private final ArrayBlockingQueue<RawLogRecord> queue;
    private final int capacity;
    private final int warningThreshold;
    private final int criticalThreshold;

    public PipelineQueue(int capacity, double warningRatio, double criticalRatio) {
        int normalizedCapacity = Math.max(1, capacity);
        this.capacity = normalizedCapacity;
        this.queue = new ArrayBlockingQueue<RawLogRecord>(normalizedCapacity);

        double warning = normalizeRatio(warningRatio, 0.7d);
        double critical = normalizeRatio(criticalRatio, 0.9d);
        if (warning > critical) {
            warning = critical;
        }
        this.warningThreshold = (int) Math.floor(normalizedCapacity * warning);
        this.criticalThreshold = (int) Math.floor(normalizedCapacity * critical);
    }

    public OfferResult offer(RawLogRecord record) {
        if (record == null) {
            return OfferResult.BACKPRESSURE_REJECTED;
        }
        int currentSize = queue.size();
        if (currentSize >= capacity) {
            return OfferResult.FULL;
        }
        if (currentSize >= criticalThreshold && !isHighPriority(record.level)) {
            return OfferResult.BACKPRESSURE_REJECTED;
        }
        if (currentSize >= warningThreshold && isDebugOrTrace(record.level)) {
            return OfferResult.BACKPRESSURE_REJECTED;
        }
        return queue.offer(record) ? OfferResult.ACCEPTED : OfferResult.FULL;
    }

    /**
     * 关闭移交阶段使用：绕过背压分级，尽力把已弹出的记录放回队列。
     */
    public boolean forceOffer(RawLogRecord record) {
        if (record == null) {
            return false;
        }
        return queue.offer(record);
    }

    public RawLogRecord poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public double utilization() {
        return (double) queue.size() / (double) capacity;
    }

    private static boolean isHighPriority(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
    }

    private static boolean isDebugOrTrace(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "DEBUG".equals(v) || "TRACE".equals(v);
    }

    private static double normalizeRatio(double value, double fallback) {
        if (Double.isNaN(value) || value <= 0.0d) {
            return fallback;
        }
        if (value >= 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
