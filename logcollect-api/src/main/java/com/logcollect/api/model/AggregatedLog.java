package com.logcollect.api.model;

import java.time.LocalDateTime;

/**
 * 聚合日志块（AGGREGATE 模式使用）。
 *
 * <p>由缓冲区 flush 时构建，包含一段时间内所有格式化后的日志行拼接体。
 */
public final class AggregatedLog {
    /** 本次 flush 的唯一标识（UUID），用于幂等写入。 */
    private final String flushId;

    /** 聚合后的完整日志文本（多行，由 formattedLine + separator 拼接） */
    private final String content;

    /** 本次聚合体包含的日志条数 */
    private final int entryCount;

    /** 本次聚合体的估算总字节数 */
    private final long totalBytes;

    /** 本次聚合体中的最高日志级别 */
    private final String maxLevel;

    /** 本次聚合体中第一条日志的时间 */
    private final LocalDateTime firstLogTime;

    /** 本次聚合体中最后一条日志的时间 */
    private final LocalDateTime lastLogTime;

    /**
     * 是否为最终刷写（方法结束触发）。
     *
     * <ul>
     *   <li>true: 方法已结束，这是缓冲区中最后一批日志</li>
     *   <li>false: 中途阈值触发的刷写，后续可能还有更多日志</li>
     * </ul>
     */
    private final boolean finalFlush;

    public AggregatedLog(String flushId,
                         String content,
                         int entryCount,
                         long totalBytes,
                         String maxLevel,
                         LocalDateTime firstLogTime,
                         LocalDateTime lastLogTime,
                         boolean finalFlush) {
        this.flushId = flushId;
        this.content = content;
        this.entryCount = entryCount;
        this.totalBytes = totalBytes;
        this.maxLevel = maxLevel;
        this.firstLogTime = firstLogTime;
        this.lastLogTime = lastLogTime;
        this.finalFlush = finalFlush;
    }

    public String getFlushId() {
        return flushId;
    }

    public String getContent() {
        return content;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public String getMaxLevel() {
        return maxLevel;
    }

    public LocalDateTime getFirstLogTime() {
        return firstLogTime;
    }

    public LocalDateTime getLastLogTime() {
        return lastLogTime;
    }

    public boolean isFinalFlush() {
        return finalFlush;
    }
}
