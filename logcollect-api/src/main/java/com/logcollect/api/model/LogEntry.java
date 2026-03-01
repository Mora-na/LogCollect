package com.logcollect.api.model;

import java.time.LocalDateTime;

/**
 * 单条日志数据载体（不可变、线程安全）。
 *
 * <p>设计原则：
 * <ul>
 *   <li>纯数据载体——不承担格式化、净化、过滤等职责</li>
 *   <li>完全自包含——不持有原始日志事件引用（防回收/泄漏）</li>
 *   <li>所有字段 final——跨线程传递安全</li>
 * </ul>
 *
 * <p>由 Appender 适配层从 ILoggingEvent（Logback）或 LogEvent（Log4j2）
 * 中提取必要字段后构建，构建后立即释放对原始事件的引用。
 */
public final class LogEntry {

    /** 本次日志收集的追踪 ID（来自 MDC） */
    private final String traceId;

    /** 日志消息内容（经过安全流水线处理后的最终内容） */
    private final String content;

    /** 日志级别（如 "INFO"、"ERROR"） */
    private final String level;

    /** 日志产生时间 */
    private final LocalDateTime time;

    /** 日志产生时间的 epoch 毫秒数（用于高效排序/比较） */
    private final long timestamp;

    /** 产生日志的线程名 */
    private final String threadName;

    /** 全限定 Logger 名称 */
    private final String loggerName;

    /**
     * 异常堆栈的字符串表示。
     *
     * <p>由 Appender 层从原始事件的 ThrowableProxy/Throwable 中提取并格式化。
     * 经安全流水线的 sanitizeThrowable() 处理（保留换行，清理危险字符）。
     * 无异常时为 null。
     */
    private final String throwableString;

    private LogEntry(Builder builder) {
        this.traceId = builder.traceId;
        this.content = builder.content;
        this.level = builder.level;
        this.time = builder.time;
        this.timestamp = builder.timestamp;
        this.threadName = builder.threadName;
        this.loggerName = builder.loggerName;
        this.throwableString = builder.throwableString;
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

    public LocalDateTime getTime() {
        return time;
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

    /** 是否包含异常信息 */
    public boolean hasThrowable() {
        return throwableString != null && !throwableString.isEmpty();
    }

    /**
     * 估算本条日志的堆内存占用（字节）。
     *
     * <p>采用保守估算策略，避免低估导致全局缓冲内存超限。
     */
    public long estimateBytes() {
        long size = 80; // LogEntry 对象自身
        size += 48; // LocalDateTime 对象
        size += estimateString(traceId);
        size += estimateString(content);
        size += estimateString(level);
        size += estimateString(threadName);
        size += estimateString(loggerName);
        size += estimateString(throwableString);
        return size;
    }

    private static long estimateString(String s) {
        if (s == null) {
            return 0;
        }
        return 40 + (long) s.length() * 2;
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

        public LogEntry build() {
            return new LogEntry(this);
        }
    }
}
