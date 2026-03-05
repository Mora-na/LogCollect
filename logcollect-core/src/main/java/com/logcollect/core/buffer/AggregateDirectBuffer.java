package com.logcollect.core.buffer;

import com.logcollect.api.format.LogLinePatternParser;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.core.pipeline.MutableProcessedLogRecord;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * AGGREGATE 模式直写 StringBuilder，绕过 LogEntry/LogSegment 中间对象。
 */
public final class AggregateDirectBuffer {

    private final StringBuilder builder;
    private final int initialCapacity;
    private final int maxEntryCount;
    private final long maxBytes;

    private int entryCount;
    private long totalBytes;
    private String maxLevel;
    private int maxLevelOrdinal;
    private long firstTimestamp;
    private long lastTimestamp;
    private int patternVersion;

    public AggregateDirectBuffer(int maxEntryCount, long maxBytes) {
        this.maxEntryCount = Math.max(1, maxEntryCount);
        this.maxBytes = Math.max(1L, maxBytes);
        this.initialCapacity = estimateInitialCapacity(this.maxEntryCount);
        this.builder = new StringBuilder(initialCapacity);
        reset();
    }

    public void append(MutableProcessedLogRecord record, String pattern) {
        if (entryCount > 0) {
            builder.append('\n');
        }
        int before = builder.length();
        LogLinePatternParser.formatRawTo(
                builder,
                record.getTraceId(),
                record.getProcessedMessage(),
                record.getLevel(),
                record.getTimestamp(),
                record.getThreadName(),
                record.getLoggerName(),
                record.getProcessedThrowable(),
                record.getMdcContext(),
                pattern);
        int writtenChars = builder.length() - before;
        totalBytes += ((long) writtenChars << 1);
        entryCount++;

        int ord = levelOrdinal(record.getLevel());
        if (ord > maxLevelOrdinal) {
            maxLevelOrdinal = ord;
            maxLevel = record.getLevel();
        }
        if (firstTimestamp == 0L) {
            firstTimestamp = record.getTimestamp();
        }
        lastTimestamp = record.getTimestamp();
    }

    public boolean shouldFlush() {
        return entryCount >= maxEntryCount || totalBytes >= maxBytes;
    }

    public boolean hasEntries() {
        return entryCount > 0;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public int getPatternVersion() {
        return patternVersion;
    }

    public void setPatternVersion(int patternVersion) {
        this.patternVersion = patternVersion;
    }

    public AggregatedLog buildAndReset(boolean finalFlush) {
        if (entryCount <= 0) {
            return null;
        }
        long first = firstTimestamp <= 0L ? System.currentTimeMillis() : firstTimestamp;
        long last = lastTimestamp <= 0L ? first : lastTimestamp;
        AggregatedLog aggregated = new AggregatedLog(
                UUID.randomUUID().toString(),
                builder.toString(),
                entryCount,
                totalBytes,
                maxLevel == null ? "TRACE" : maxLevel,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(first), ZoneId.systemDefault()),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(last), ZoneId.systemDefault()),
                finalFlush);
        reset();
        return aggregated;
    }

    private void reset() {
        builder.setLength(0);
        if (builder.capacity() > initialCapacity * 4) {
            builder.trimToSize();
            builder.ensureCapacity(initialCapacity);
        }
        entryCount = 0;
        totalBytes = 0L;
        maxLevel = null;
        maxLevelOrdinal = -1;
        firstTimestamp = 0L;
        lastTimestamp = 0L;
    }

    private int estimateInitialCapacity(int maxEntries) {
        return (int) (maxEntries * 150 * 0.8d);
    }

    private int levelOrdinal(String level) {
        if (level == null) {
            return 0;
        }
        String v = level.toUpperCase();
        if ("FATAL".equals(v)) return 5;
        if ("ERROR".equals(v)) return 4;
        if ("WARN".equals(v)) return 3;
        if ("INFO".equals(v)) return 2;
        if ("DEBUG".equals(v)) return 1;
        return 0;
    }
}
