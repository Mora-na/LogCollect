package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 端到端唯一安全处理入口。
 */
public class SecurityPipeline {

    private final LogSanitizer sanitizer;
    private final LogMasker masker;

    public SecurityPipeline(LogSanitizer sanitizer, LogMasker masker) {
        this.sanitizer = sanitizer;
        this.masker = masker;
    }

    public LogEntry process(LogEntry rawEntry) {
        return process(rawEntry, null);
    }

    public LogEntry process(LogEntry rawEntry, SecurityMetrics metrics) {
        if (rawEntry == null) {
            return null;
        }

        SinglePassSecurityProcessor processor = new SinglePassSecurityProcessor(sanitizer, masker);
        SinglePassSecurityProcessor.ProcessResult contentResult =
                processor.process(rawEntry.getContent(), true);
        SinglePassSecurityProcessor.ProcessResult throwableResult =
                processor.process(rawEntry.getThrowableString(), false);

        if (metrics != null) {
            if (contentResult.isSanitizedModified()) {
                metrics.onContentSanitized();
            }
            if (throwableResult.isSanitizedModified()) {
                metrics.onThrowableSanitized();
            }
            if (contentResult.isMaskedModified()) {
                metrics.onContentMasked();
            }
            if (throwableResult.isMaskedModified()) {
                metrics.onThrowableMasked();
            }
        }

        return LogEntry.builder()
                .traceId(rawEntry.getTraceId())
                .content(contentResult.getValue())
                .level(sanitizeField(rawEntry.getLevel()))
                .timestamp(rawEntry.getTimestamp())
                .threadName(sanitizeField(rawEntry.getThreadName()))
                .loggerName(sanitizeField(rawEntry.getLoggerName()))
                .throwableString(throwableResult.getValue())
                .mdcContext(sanitizeMdc(rawEntry.getMdcContext()))
                .build();
    }

    private String sanitizeField(String value) {
        if (sanitizer == null) {
            return value;
        }
        return sanitizer.sanitize(value);
    }

    private Map<String, String> sanitizeMdc(Map<String, String> mdcContext) {
        if (mdcContext == null || mdcContext.isEmpty()) {
            return Collections.emptyMap();
        }
        if (sanitizer == null) {
            return mdcContext;
        }
        Map<String, String> sanitized = new HashMap<String, String>(mdcContext.size());
        for (Map.Entry<String, String> entry : mdcContext.entrySet()) {
            String key = sanitizeField(entry.getKey());
            String value = sanitizeField(entry.getValue());
            sanitized.put(key, value);
        }
        return sanitized;
    }

    public interface SecurityMetrics {
        default void onContentSanitized() {
        }

        default void onThrowableSanitized() {
        }

        default void onContentMasked() {
        }

        default void onThrowableMasked() {
        }
    }
}
