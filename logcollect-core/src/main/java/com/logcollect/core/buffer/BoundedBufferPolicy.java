package com.logcollect.core.buffer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地缓冲区容量策略。
 *
 * <p>负责在写入前做容量判断，并根据溢出策略决定是提前 flush、丢弃最旧还是丢弃最新。
 */
public class BoundedBufferPolicy {

    /**
     * 缓冲区溢出时的处理策略。
     */
    public enum OverflowStrategy {
        /** 触发一次提前 flush，再继续尝试写入。 */
        FLUSH_EARLY,
        /** 由调用方先淘汰最旧元素后再写入。 */
        DROP_OLDEST,
        /** 直接拒绝当前写入。 */
        DROP_NEWEST
    }

    /**
     * 写入前检查的判定结果。
     */
    public enum RejectReason {
        /** 接受写入。 */
        ACCEPTED,
        /** 本地缓冲已满。 */
        BUFFER_FULL,
        /** 全局内存预算不足。 */
        GLOBAL_MEMORY_LIMIT
    }

    private final long maxBytes;
    private final int maxEntries;
    private final OverflowStrategy strategy;

    private final AtomicLong currentBytes = new AtomicLong(0);
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * 创建容量策略。
     *
     * @param maxBytes   本地最大字节数（小于等于 0 表示不限制）
     * @param maxEntries 本地最大条数（小于等于 0 表示不限制）
     * @param strategy   溢出策略，为 null 时默认 {@link OverflowStrategy#FLUSH_EARLY}
     */
    public BoundedBufferPolicy(long maxBytes, int maxEntries, OverflowStrategy strategy) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.strategy = strategy == null ? OverflowStrategy.FLUSH_EARLY : strategy;
    }

    /**
     * 写入前检查并更新计数。
     *
     * @param entryBytes  单条日志估算字节数
     * @param earlyFlush  当策略为 {@code FLUSH_EARLY} 时执行的提前 flush 回调，可为 null
     * @return 判定结果
     */
    public RejectReason beforeAdd(long entryBytes, Runnable earlyFlush) {
        if (isOverflow(entryBytes)) {
            switch (strategy) {
                case FLUSH_EARLY:
                    if (earlyFlush != null) {
                        earlyFlush.run();
                    }
                    break;
                case DROP_NEWEST:
                    droppedCount.incrementAndGet();
                    return RejectReason.BUFFER_FULL;
                case DROP_OLDEST:
                    break;
                default:
                    break;
            }
        }
        currentBytes.addAndGet(entryBytes);
        currentCount.incrementAndGet();
        return RejectReason.ACCEPTED;
    }

    /**
     * drain/flush 后回收计数。
     *
     * @param bytesRemoved 回收字节数
     * @param countRemoved 回收条数
     */
    public void afterDrain(long bytesRemoved, int countRemoved) {
        if (bytesRemoved > 0) {
            long left = currentBytes.addAndGet(-bytesRemoved);
            if (left < 0) {
                currentBytes.set(0);
            }
        }
        if (countRemoved > 0) {
            long left = currentCount.addAndGet(-countRemoved);
            if (left < 0) {
                currentCount.set(0);
            }
        }
    }

    /**
     * 记录一次丢弃事件。
     */
    public void recordDropped() {
        droppedCount.incrementAndGet();
    }

    /**
     * 获取累计丢弃条数。
     *
     * @return 累计丢弃数
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /**
     * 获取当前估算占用字节数。
     *
     * @return 当前字节数
     */
    public long getCurrentBytes() {
        return currentBytes.get();
    }

    /**
     * 获取当前条数。
     *
     * @return 当前条数
     */
    public int getCurrentCount() {
        return currentCount.get();
    }

    /**
     * 获取溢出策略。
     *
     * @return 溢出策略
     */
    public OverflowStrategy getStrategy() {
        return strategy;
    }

    /**
     * 判断追加后是否会超过容量阈值。
     *
     * @param entryBytes 单条日志估算字节数
     * @return true 表示会溢出
     */
    public boolean isOverflow(long entryBytes) {
        boolean bytesOverflow = maxBytes > 0 && currentBytes.get() + entryBytes > maxBytes;
        boolean entriesOverflow = maxEntries > 0 && currentCount.get() >= maxEntries;
        return bytesOverflow || entriesOverflow;
    }
}
