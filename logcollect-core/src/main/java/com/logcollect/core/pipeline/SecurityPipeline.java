package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.StringLengthGuard;

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
    private final StringLengthGuard lengthGuard;

    public SecurityPipeline(LogSanitizer sanitizer, LogMasker masker) {
        this(sanitizer, masker, null);
    }

    public SecurityPipeline(LogSanitizer sanitizer, LogMasker masker, StringLengthGuard lengthGuard) {
        this.sanitizer = sanitizer;
        this.masker = masker;
        this.lengthGuard = lengthGuard;
    }

    public LogEntry process(LogEntry rawEntry) {
        return process(rawEntry, SecurityMetrics.NOOP);
    }

    public LogEntry process(LogEntry rawEntry, SecurityMetrics metrics) {
        if (rawEntry == null) {
            return null;
        }
        return processRaw(
                rawEntry.getTraceId(),
                rawEntry.getContent(),
                rawEntry.getLevel(),
                rawEntry.getTimestamp(),
                rawEntry.getThreadName(),
                rawEntry.getLoggerName(),
                rawEntry.getThrowableString(),
                rawEntry.getMdcContext(),
                metrics);
    }

    /**
     * 从原始字段直接构建安全 LogEntry，避免中间态 LogEntry 分配。
     */
    public LogEntry processRaw(String traceId,
                               String content,
                               String level,
                               long timestamp,
                               String threadName,
                               String loggerName,
                               String throwableString,
                               Map<String, String> mdcContext,
                               SecurityMetrics metrics) {
        SecurityMetrics metricsRef = metrics == null ? SecurityMetrics.NOOP : metrics;

        SinglePassSecurityProcessor processor = new SinglePassSecurityProcessor(sanitizer, masker);
        SinglePassSecurityProcessor.ProcessResult contentResult =
                processor.process(content, true);
        SinglePassSecurityProcessor.ProcessResult throwableResult =
                processor.process(throwableString, false);

        if (contentResult.isSanitizedModified()) {
            metricsRef.onContentSanitized();
        }
        if (throwableResult.isSanitizedModified()) {
            metricsRef.onThrowableSanitized();
        }
        if (contentResult.isMaskedModified()) {
            metricsRef.onContentMasked();
        }
        if (throwableResult.isMaskedModified()) {
            metricsRef.onThrowableMasked();
        }

        String guardedContent = contentResult.getValue();
        String guardedThrowable = throwableResult.getValue();
        if (lengthGuard != null) {
            guardedContent = lengthGuard.guardContent(guardedContent);
            guardedThrowable = lengthGuard.guardThrowable(guardedThrowable);
        }

        return LogEntry.builder()
                .traceId(traceId)
                .content(guardedContent)
                .level(sanitizeField(level))
                .timestamp(timestamp)
                .threadName(sanitizeField(threadName))
                .loggerName(sanitizeField(loggerName))
                .throwableString(guardedThrowable)
                .mdcContext(sanitizeMdc(mdcContext))
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
        if (sanitizer == null && masker == null && lengthGuard == null) {
            return mdcContext;
        }
        if (canReuseMdcReference(mdcContext)) {
            return mdcContext;
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

    private boolean canReuseMdcReference(Map<String, String> mdcContext) {
        if (lengthGuard != null) {
            return false;
        }
        for (Map.Entry<String, String> entry : mdcContext.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (needsMdcKeyNormalization(key) || needsSanitization(value) || needsMasking(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean needsMdcKeyNormalization(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            return true;
        }
        if (needsSanitization(key)) {
            return true;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            if (!allowed) {
                return true;
            }
        }
        return false;
    }

    private boolean needsMasking(String value) {
        if (masker == null || value == null || value.isEmpty()) {
            return false;
        }
        if (masker instanceof DefaultLogMasker) {
            return ((DefaultLogMasker) masker).hasPotentialMatch(value);
        }
        return true;
    }

    private boolean needsSanitization(String value) {
        if (sanitizer == null || value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\u001B' || c == '<' || c == '>') {
                return true;
            }
        }
        return false;
    }

    private String sanitizeMdcValue(String value) {
        String safeValue = value;
        if (sanitizer != null) {
            safeValue = sanitizer.sanitize(safeValue);
        }
        if (masker != null) {
            safeValue = masker.mask(safeValue);
        }
        if (lengthGuard != null) {
            safeValue = lengthGuard.guardContent(safeValue);
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

        SecurityMetrics NOOP = new SecurityMetrics() {
        };
    }
}
