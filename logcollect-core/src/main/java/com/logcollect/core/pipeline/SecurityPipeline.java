package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;

/**
 * 安全处理流水线：对 LogEntry 的内容和异常堆栈分别执行净化与脱敏。
 *
 * <p>处理顺序：
 * <ol>
 *   <li>消息内容：sanitize(content)</li>
 *   <li>异常堆栈：sanitizeThrowable(throwable)</li>
 *   <li>消息内容脱敏：mask(content)</li>
 *   <li>异常堆栈脱敏：mask(throwable)</li>
 * </ol>
 */
public class SecurityPipeline {

    private final LogSanitizer sanitizer;
    private final LogMasker masker;

    public SecurityPipeline(LogSanitizer sanitizer, LogMasker masker) {
        this.sanitizer = sanitizer;
        this.masker = masker;
    }

    /**
     * 执行安全流水线处理。
     *
     * @param rawEntry 原始 LogEntry（来自 Appender 层）
     * @return 处理后的安全 LogEntry
     */
    public LogEntry process(LogEntry rawEntry) {
        String safeContent = rawEntry.getContent();
        if (sanitizer != null) {
            safeContent = sanitizer.sanitize(safeContent);
        }
        if (masker != null) {
            safeContent = masker.mask(safeContent);
        }

        String safeThrowable = null;
        if (rawEntry.hasThrowable()) {
            safeThrowable = rawEntry.getThrowableString();
            if (sanitizer != null) {
                safeThrowable = sanitizer.sanitizeThrowable(safeThrowable);
            }
            if (masker != null) {
                safeThrowable = masker.mask(safeThrowable);
            }
        }

        return LogEntry.builder()
                .traceId(rawEntry.getTraceId())
                .content(safeContent)
                .level(rawEntry.getLevel())
                .time(rawEntry.getTime())
                .timestamp(rawEntry.getTimestamp())
                .threadName(rawEntry.getThreadName())
                .loggerName(rawEntry.getLoggerName())
                .throwableString(safeThrowable)
                .build();
    }
}
