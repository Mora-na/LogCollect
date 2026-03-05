package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.StringLengthGuard;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 端到端唯一安全处理入口。
 */
public class SecurityPipeline {
    private static final Pattern SAFE_MDC_KEY_PATTERN = Pattern.compile("[a-zA-Z0-9._\\-]+");
    private static final Set<String> FRAMEWORK_MDC_KEYS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("_logCollect_traceId", "traceId")));

    private final LogSanitizer sanitizer;
    private final LogMasker masker;
    private final StringLengthGuard lengthGuard;
    private final SinglePassSecurityProcessor processor;

    public SecurityPipeline(LogSanitizer sanitizer, LogMasker masker) {
        this(sanitizer, masker, null);
    }

    public SecurityPipeline(LogSanitizer sanitizer, LogMasker masker, StringLengthGuard lengthGuard) {
        this.sanitizer = sanitizer;
        this.masker = masker;
        this.lengthGuard = lengthGuard;
        this.processor = new SinglePassSecurityProcessor(sanitizer, masker);
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
        return processRawRecord(
                traceId,
                content,
                level,
                timestamp,
                threadName,
                loggerName,
                throwableString,
                mdcContext,
                metrics).toLogEntry();
    }

    /**
     * 从原始字段直接生成安全字段结果，用于 AGGREGATE 场景避免构建完整 LogEntry。
     */
    public ProcessedLogRecord processRawRecord(String traceId,
                                               String content,
                                               String level,
                                               long timestamp,
                                               String threadName,
                                               String loggerName,
                                               String throwableString,
                                               Map<String, String> mdcContext,
                                               SecurityMetrics metrics) {
        return processRawRecordWithDeadline(traceId, content, level, timestamp, threadName, loggerName,
                throwableString, mdcContext, metrics, Long.MAX_VALUE);
    }

    public ProcessedLogRecord processRawRecordWithDeadline(String traceId,
                                                           String content,
                                                           String level,
                                                           long timestamp,
                                                           String threadName,
                                                           String loggerName,
                                                           String throwableString,
                                                           Map<String, String> mdcContext,
                                                           SecurityMetrics metrics,
                                                           long deadlineNanos) {
        SecurityMetrics metricsRef = metrics == null ? SecurityMetrics.NOOP : metrics;

        SinglePassSecurityProcessor.ProcessResult contentResult;
        if (isTimedOut(deadlineNanos)) {
            metricsRef.onPipelineTimeout("content");
            contentResult = new SinglePassSecurityProcessor.ProcessResult(content, false, false, false);
        } else {
            contentResult = processor.process(content, true);
        }

        SinglePassSecurityProcessor.ProcessResult throwableResult;
        if (throwableString == null) {
            throwableResult = new SinglePassSecurityProcessor.ProcessResult(null, false, false, false);
        } else if (isTimedOut(deadlineNanos)) {
            metricsRef.onPipelineTimeout("throwable");
            throwableResult = new SinglePassSecurityProcessor.ProcessResult(throwableString, false, false, false);
        } else {
            throwableResult = processor.process(throwableString, false);
        }

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
        if (contentResult.isFastPathHit()) {
            metricsRef.onFastPathHit();
        }
        if (throwableResult.isFastPathHit()) {
            metricsRef.onFastPathHit();
        }

        String guardedContent = contentResult.getValue();
        String guardedThrowable = throwableResult.getValue();
        if (lengthGuard != null) {
            guardedContent = lengthGuard.guardContent(guardedContent);
            guardedThrowable = lengthGuard.guardThrowable(guardedThrowable);
        }

        Map<String, String> safeMdc;
        if (isTimedOut(deadlineNanos)) {
            metricsRef.onPipelineTimeout("mdc");
            safeMdc = mdcContext == null ? Collections.emptyMap() : mdcContext;
        } else {
            safeMdc = sanitizeMdc(mdcContext);
        }

        return new ProcessedLogRecord(
                traceId,
                guardedContent,
                level,
                timestamp,
                threadName,
                loggerName,
                guardedThrowable,
                safeMdc);
    }

    private boolean isTimedOut(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
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
            String rawKey = entry.getKey();
            if (isTrustedFrameworkMdcKey(rawKey)) {
                sanitized.put(rawKey, entry.getValue());
                continue;
            }
            String key = sanitizeMdcKey(rawKey);
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
            if (isTrustedFrameworkMdcKey(key)) {
                continue;
            }
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

    private boolean isTrustedFrameworkMdcKey(String key) {
        return key != null && FRAMEWORK_MDC_KEYS.contains(key);
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

        default void onFastPathHit() {
        }

        default void onPipelineTimeout(String step) {
        }

        SecurityMetrics NOOP = new SecurityMetrics() {
        };
    }

    public static final class ProcessedLogRecord {
        private final String traceId;
        private final String content;
        private final String level;
        private final long timestamp;
        private final String threadName;
        private final String loggerName;
        private final String throwableString;
        private final Map<String, String> mdcContext;

        private ProcessedLogRecord(String traceId,
                                   String content,
                                   String level,
                                   long timestamp,
                                   String threadName,
                                   String loggerName,
                                   String throwableString,
                                   Map<String, String> mdcContext) {
            this.traceId = traceId;
            this.content = content;
            this.level = level;
            this.timestamp = timestamp;
            this.threadName = threadName;
            this.loggerName = loggerName;
            this.throwableString = throwableString;
            this.mdcContext = mdcContext;
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

        public long estimateBytes() {
            long size = 112L;
            size += estimateString(traceId);
            size += estimateString(content);
            size += estimateString(level);
            size += estimateString(threadName);
            size += estimateString(loggerName);
            size += estimateString(throwableString);
            if (mdcContext != null && !mdcContext.isEmpty()) {
                size += 64L;
                for (Map.Entry<String, String> entry : mdcContext.entrySet()) {
                    size += 32L;
                    size += estimateString(entry.getKey());
                    size += estimateString(entry.getValue());
                }
            }
            return size;
        }

        public LogEntry toLogEntry() {
            return LogEntry.builder()
                    .traceId(traceId)
                    .content(content)
                    // trusted source: framework level enum/string
                    .level(level)
                    .timestamp(timestamp)
                    // trusted source: Thread#getName()
                    .threadName(threadName)
                    // trusted source: Logger#getName()
                    .loggerName(loggerName)
                    .throwableString(throwableString)
                    .mdcContext(mdcContext)
                    .build();
        }

        private long estimateString(String value) {
            if (value == null) {
                return 0L;
            }
            return 48L + ((long) value.length() << 1);
        }
    }
}
