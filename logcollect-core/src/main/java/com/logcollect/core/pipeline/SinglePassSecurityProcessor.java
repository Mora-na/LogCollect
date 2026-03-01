package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;

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
        if (input == null || input.isEmpty()) {
            return new ProcessResult(input, false, false);
        }
        if (canFastPath(input, strict)) {
            return new ProcessResult(input, false, false);
        }
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

    private boolean canFastPath(String input, boolean strict) {
        return canSkipSanitize(input, strict) && canSkipMask(input);
    }

    private boolean canSkipSanitize(String input, boolean strict) {
        if (sanitizer == null) {
            return true;
        }
        if (!(sanitizer instanceof DefaultLogSanitizer)) {
            return false;
        }
        return isClean(input, strict);
    }

    private boolean canSkipMask(String input) {
        if (masker == null) {
            return true;
        }
        if (masker instanceof DefaultLogMasker) {
            return !((DefaultLogMasker) masker).hasPotentialMatch(input);
        }
        return false;
    }

    private boolean isClean(String input, boolean strict) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<' || c == 27) {
                return false;
            }
            if (strict) {
                if (Character.isISOControl(c)) {
                    return false;
                }
            } else if (isDangerousThrowableControl(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDangerousThrowableControl(char c) {
        if (c == '\n' || c == '\r' || c == '\t') {
            return false;
        }
        return Character.isISOControl(c);
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
