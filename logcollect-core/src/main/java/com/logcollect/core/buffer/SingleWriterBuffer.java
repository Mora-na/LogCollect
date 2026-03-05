package com.logcollect.core.buffer;

import com.logcollect.api.model.LogEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单写者缓冲区：同一时刻仅允许一个线程写入。
 */
public final class SingleWriterBuffer {

    private final ArrayList<LogEntry> entries;
    private int count;
    private long bytes;
    private boolean flushing;

    private final int maxSize;
    private final long maxBytes;

    private volatile boolean closing;
    private volatile boolean closed;
    private volatile int totalCollected;
    private volatile long totalBytes;

    public SingleWriterBuffer(int maxSize, long maxBytes, int initialCapacity) {
        this.maxSize = Math.max(1, maxSize);
        this.maxBytes = maxBytes;
        this.entries = new ArrayList<LogEntry>(Math.max(1, Math.min(initialCapacity, this.maxSize)));
    }

    public void offer(LogEntry entry, long entryBytes) {
        entries.add(entry);
        count++;
        bytes += entryBytes;
        totalCollected++;
        totalBytes += entryBytes;
    }

    public boolean shouldFlush() {
        return count >= maxSize || (maxBytes > 0 && bytes >= maxBytes);
    }

    public List<LogEntry> drain(ArrayList<LogEntry> reusableList) {
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        reusableList.clear();
        reusableList.addAll(entries);
        entries.clear();
        count = 0;
        bytes = 0;
        return reusableList;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public int getCount() {
        return count;
    }

    public long getBytes() {
        return bytes;
    }

    public int getTotalCollected() {
        return totalCollected;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public boolean isClosing() {
        return closing;
    }

    public void markClosing() {
        this.closing = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public void markClosed() {
        this.closed = true;
    }

    public boolean isFlushing() {
        return flushing;
    }

    public void setFlushing(boolean flushing) {
        this.flushing = flushing;
    }

    public List<LogEntry> snapshotEntries() {
        return new ArrayList<LogEntry>(entries);
    }
}
