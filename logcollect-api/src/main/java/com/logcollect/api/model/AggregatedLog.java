package com.logcollect.api.model;

import java.time.LocalDateTime;

public class AggregatedLog {
    private final String content;
    private final int entryCount;
    private final long totalBytes;
    private final String maxLevel;
    private final LocalDateTime firstLogTime;
    private final LocalDateTime lastLogTime;
    private final boolean finalFlush;

    public AggregatedLog(String content,
                         int entryCount,
                         long totalBytes,
                         String maxLevel,
                         LocalDateTime firstLogTime,
                         LocalDateTime lastLogTime,
                         boolean finalFlush) {
        this.content = content;
        this.entryCount = entryCount;
        this.totalBytes = totalBytes;
        this.maxLevel = maxLevel;
        this.firstLogTime = firstLogTime;
        this.lastLogTime = lastLogTime;
        this.finalFlush = finalFlush;
    }

    public String getContent() { return content; }
    public int getEntryCount() { return entryCount; }
    public long getTotalBytes() { return totalBytes; }
    public String getMaxLevel() { return maxLevel; }
    public LocalDateTime getFirstLogTime() { return firstLogTime; }
    public LocalDateTime getLastLogTime() { return lastLogTime; }
    public boolean isFinalFlush() { return finalFlush; }
}
