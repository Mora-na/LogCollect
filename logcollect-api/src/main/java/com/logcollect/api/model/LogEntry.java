package com.logcollect.api.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单条日志数据载体（不可变、线程安全）。
 */
public final class LogEntry {

    private final String traceId;
    private final String content;
    private final String level;
    /** 唯一时间源，epoch 毫秒。 */
    private final long timestamp;
    private final String threadName;
    private final String loggerName;
    private final String throwableString;
    private final Map<String, String> mdcContext;

    private LogEntry(Builder builder) {
        this.traceId = builder.traceId;
        this.content = builder.content;
        this.level = builder.level;
        this.timestamp = resolveTimestamp(builder);
        this.threadName = builder.threadName;
        this.loggerName = builder.loggerName;
        this.throwableString = builder.throwableString;
        if (builder.mdcContext == null || builder.mdcContext.isEmpty()) {
            this.mdcContext = Collections.emptyMap();
        } else {
            this.mdcContext = Collections.unmodifiableMap(new HashMap<String, String>(builder.mdcContext));
        }
    }

    private long resolveTimestamp(Builder builder) {
        if (builder.timestamp > 0L) {
            return builder.timestamp;
        }
        if (builder.time != null) {
            return builder.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    public String getTraceId() {
        return traceId;
    }

    public String getContent() {
        return content;
    }

    public String getLevel() {
        return level;
    }

    /**
     * 保留原有访问方式，按 timestamp 延迟计算本地时间。
     */
    public LocalDateTime getTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getThrowableString() {
        return throwableString;
    }

    public Map<String, String> getMdcContext() {
        return mdcContext;
    }

    public boolean hasThrowable() {
        return throwableString != null && !throwableString.isEmpty();
    }

    public long estimateBytes() {
        long size = 112;
        size += estimateString(traceId);
        size += estimateString(content);
        size += estimateString(level);
        size += estimateString(threadName);
        size += estimateString(loggerName);
        size += estimateString(throwableString);
        if (!mdcContext.isEmpty()) {
            size += 64;
            for (Map.Entry<String, String> entry : mdcContext.entrySet()) {
                size += 32;
                size += estimateString(entry.getKey());
                size += estimateString(entry.getValue());
            }
        }
        return size;
    }

    private static long estimateString(String value) {
        if (value == null) {
            return 0;
        }
        return 48L + ((long) value.length() << 1);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String traceId;
        private String content;
        private String level;
        private LocalDateTime time;
        private long timestamp;
        private String threadName;
        private String loggerName;
        private String throwableString;
        private Map<String, String> mdcContext;

        private Builder() {
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder level(String level) {
            this.level = level;
            return this;
        }

        public Builder time(LocalDateTime time) {
            this.time = time;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public Builder loggerName(String loggerName) {
            this.loggerName = loggerName;
            return this;
        }

        public Builder throwableString(String throwableString) {
            this.throwableString = throwableString;
            return this;
        }

        public Builder mdcContext(Map<String, String> mdcContext) {
            this.mdcContext = mdcContext;
            return this;
        }

        public LogEntry build() {
            return new LogEntry(this);
        }
    }
}
