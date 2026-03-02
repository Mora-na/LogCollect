package com.logcollect.core.buffer;

import com.logcollect.api.model.LogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单日志条目缓冲区（按条存储）。
 *
 * <p>用于 SINGLE 模式等场景，提供入队、批量 drain 与阈值判断能力。
 */
public class LogBuffer {
    private final ConcurrentLinkedQueue<LogEntry> entries = new ConcurrentLinkedQueue<LogEntry>();
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicLong bytesUsed = new AtomicLong(0);
    private final int maxSize;
    private final long maxBytes;
    private final GlobalBufferMemoryManager globalManager;

    /**
     * 创建缓冲区。
     *
     * @param maxSize       最大条数阈值
     * @param maxBytes      最大字节阈值
     * @param globalManager 全局内存管理器，可为 null
     */
    public LogBuffer(int maxSize, long maxBytes, GlobalBufferMemoryManager globalManager) {
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
    }

    /**
     * 尝试入队一条日志。
     *
     * @param entry 日志条目
     * @return true 表示入队成功
     */
    public boolean offer(LogEntry entry) {
        if (entry == null) {
            return false;
        }
        long entryBytes = entry.estimateBytes();
        if (globalManager != null && !globalManager.tryAllocate(entryBytes)) {
            return false;
        }
        entries.add(entry);
        count.incrementAndGet();
        bytesUsed.addAndGet(entryBytes);
        return true;
    }

    /**
     * drain 出全部日志并清空缓冲区。
     *
     * @return 按队列顺序返回的日志列表
     */
    public List<LogEntry> drain() {
        List<LogEntry> batch = new ArrayList<LogEntry>();
        LogEntry entry;
        long freedBytes = 0;
        while ((entry = entries.poll()) != null) {
            batch.add(entry);
            freedBytes += entry.estimateBytes();
        }
        count.set(0);
        bytesUsed.set(0);
        if (globalManager != null) {
            globalManager.release(freedBytes);
        }
        return batch;
    }

    /**
     * 判断是否达到 flush 阈值。
     *
     * @return true 表示达到条数或字节阈值
     */
    public boolean shouldFlush() {
        return count.get() >= maxSize || bytesUsed.get() >= maxBytes;
    }

    /**
     * 获取当前条数。
     *
     * @return 当前缓冲条数
     */
    public int size() {
        return count.get();
    }

    /**
     * 获取当前估算字节占用。
     *
     * @return 当前字节占用
     */
    public long bytesUsed() {
        return bytesUsed.get();
    }
}
