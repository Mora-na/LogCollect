package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 端到端唯一安全处理入口。
 */
public class SecurityPipeline {
    private static final Pattern SAFE_MDC_KEY_PATTERN = Pattern.compile("[a-zA-Z0-9._\\-]+");

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
        Map<String, String> sanitized = new LinkedHashMap<String, String>(mdcContext.size());
        for (Map.Entry<String, String> entry : mdcContext.entrySet()) {
            String key = sanitizeMdcKey(entry.getKey());
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = sanitizeMdcValue(entry.getValue());
            sanitized.put(key, value);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private String sanitizeMdcValue(String value) {
        String safeValue = value;
        if (sanitizer != null) {
            safeValue = sanitizer.sanitize(safeValue);
        }
        if (masker != null) {
            safeValue = masker.mask(safeValue);
        }
        return safeValue;
    }

    private String sanitizeMdcKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String normalized = key;
        if (sanitizer != null) {
            normalized = sanitizer.sanitize(normalized);
        }
        normalized = normalized == null ? null : (normalized.length() > 128 ? normalized.substring(0, 128) : normalized);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (SAFE_MDC_KEY_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        return normalized.replaceAll("[^a-zA-Z0-9._\\-]", "_");
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
