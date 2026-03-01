package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.Objects;

/**
 * 合并 sanitize + mask 的单入口处理器。
 */
final class SinglePassSecurityProcessor {

    private final LogSanitizer sanitizer;
    private final LogMasker masker;

    SinglePassSecurityProcessor(LogSanitizer sanitizer, LogMasker masker) {
        this.sanitizer = sanitizer;
        this.masker = masker;
    }

    ProcessResult process(String input, boolean strict) {
        String current = input;
        boolean sanitizedModified = false;
        boolean maskedModified = false;

        if (sanitizer != null) {
            String sanitized = strict ? sanitizer.sanitize(current) : sanitizer.sanitizeThrowable(current);
            sanitizedModified = !Objects.equals(current, sanitized);
            current = sanitized;
        }
        if (masker != null) {
            String masked = masker.mask(current);
            maskedModified = !Objects.equals(current, masked);
            current = masked;
        }
        return new ProcessResult(current, sanitizedModified, maskedModified);
    }

    static final class ProcessResult {
        private final String value;
        private final boolean sanitizedModified;
        private final boolean maskedModified;

        ProcessResult(String value, boolean sanitizedModified, boolean maskedModified) {
            this.value = value;
            this.sanitizedModified = sanitizedModified;
            this.maskedModified = maskedModified;
        }

        String getValue() {
            return value;
        }

        boolean isSanitizedModified() {
            return sanitizedModified;
        }

        boolean isMaskedModified() {
            return maskedModified;
        }
    }
}
