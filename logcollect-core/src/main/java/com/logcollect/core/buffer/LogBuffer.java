package com.logcollect.core.buffer;

import com.logcollect.api.model.LogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LogBuffer {
    private final ConcurrentLinkedQueue<LogEntry> entries = new ConcurrentLinkedQueue<LogEntry>();
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicLong bytesUsed = new AtomicLong(0);
    private final int maxSize;
    private final long maxBytes;
    private final GlobalBufferMemoryManager globalManager;

    public LogBuffer(int maxSize, long maxBytes, GlobalBufferMemoryManager globalManager) {
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
    }

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

    public boolean shouldFlush() {
        return count.get() >= maxSize || bytesUsed.get() >= maxBytes;
    }

    public int size() {
        return count.get();
    }

    public long bytesUsed() {
        return bytesUsed.get();
    }
}
