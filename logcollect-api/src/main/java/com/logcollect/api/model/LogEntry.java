package com.logcollect.api.model;

import java.time.LocalDateTime;

public class LogEntry {
    private final String traceId;
    private final String content;
    private final String level;
    private final LocalDateTime time;
    private final String threadName;
    private final String loggerName;

    public LogEntry(String content, String level, LocalDateTime time) {
        this(null, content, level, time, null, null);
    }

    public LogEntry(String traceId, String content, String level, LocalDateTime time,
                    String threadName, String loggerName) {
        this.traceId = traceId;
        this.content = content;
        this.level = level;
        this.time = time;
        this.threadName = threadName;
        this.loggerName = loggerName;
    }

    public String getTraceId() { return traceId; }
    public String getContent() { return content; }
    public String getLevel() { return level; }
    public LocalDateTime getTime() { return time; }
    public String getThreadName() { return threadName; }
    public String getLoggerName() { return loggerName; }

    public long estimateBytes() {
        long size = 16;
        if (traceId != null) size += traceId.length() * 2L;
        if (content != null) size += content.length() * 2L;
        if (level != null) size += level.length() * 2L;
        if (threadName != null) size += threadName.length() * 2L;
        if (loggerName != null) size += loggerName.length() * 2L;
        return size;
    }
}
