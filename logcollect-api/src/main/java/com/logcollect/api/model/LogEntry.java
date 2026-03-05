package com.logcollect.api.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条日志数据载体（不可变、线程安全）。
 */
public final class LogEntry {

    private static final long ENTRY_OBJECT_OVERHEAD = 112L;
    public static final long STRING_OBJECT_OVERHEAD = 56L;
    private static final long MAP_NODE_OVERHEAD = 48L;
    private static final long MAP_SHELL_OVERHEAD = 208L;
    private static volatile double estimationFactor = 1.0d;

    private final String traceId;
    private final String content;
    private final String level;
    /** 唯一时间源，epoch 毫秒。 */
    private final long timestamp;
    private final String threadName;
    private final String loggerName;
    private final String throwableString;
    private final Map<String, String> mdcContext;
    private final long estimatedBytes;

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
        } else if (isUnmodifiableMap(builder.mdcContext)) {
            this.mdcContext = builder.mdcContext;
        } else {
            this.mdcContext = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.mdcContext));
        }
        this.estimatedBytes = computeEstimateBytes();
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
     *
     * @return 基于系统时区换算后的本地时间
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
        return estimatedBytes;
    }

    private long computeEstimateBytes() {
        long size = ENTRY_OBJECT_OVERHEAD;
        size += estimateStringBytes(traceId);
        size += estimateStringBytes(content);
        size += estimateStringBytes(level);
        size += estimateStringBytes(threadName);
        size += estimateStringBytes(loggerName);
        size += estimateStringBytes(throwableString);
        size += estimateMdcBytes(mdcContext);
        double factor = estimationFactor;
        if (factor != 1.0d) {
            size = (long) (size * factor);
        }
        return size;
    }

    private static long estimateMdcBytes(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return 0L;
        }
        long total = MAP_SHELL_OVERHEAD;
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            total += MAP_NODE_OVERHEAD;
            total += estimateStringBytes(entry.getKey());
            total += estimateStringBytes(entry.getValue());
        }
        return total;
    }

    private static boolean isUnmodifiableMap(Map<?, ?> map) {
        if (map == null) {
            return false;
        }
        if (map == Collections.emptyMap()) {
            return true;
        }
        String className = map.getClass().getName();
        return className.contains("Unmodifiable") || className.contains("ImmutableMap");
    }

    public static long estimateStringBytes(String value) {
        if (value == null) {
            return 0L;
        }
        long dataBytes = alignTo8((long) value.length() * 2L);
        return STRING_OBJECT_OVERHEAD + dataBytes;
    }

    public static long alignTo8(long bytes) {
        return (bytes + 7L) & ~7L;
    }

    public static void setEstimationFactor(double factor) {
        if (factor <= 0.0d) {
            throw new IllegalArgumentException("factor must be > 0, got: " + factor);
        }
        estimationFactor = factor;
    }

    public static double getEstimationFactor() {
        return estimationFactor;
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
