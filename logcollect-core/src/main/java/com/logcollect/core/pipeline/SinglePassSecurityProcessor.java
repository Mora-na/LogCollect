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
            return new ProcessResult(input, false, false, false);
        }

        int flags;
        boolean detectorAvailable = true;
        try {
            flags = strict ? FastPathDetector.scanMessage(input) : FastPathDetector.scanThrowable(input);
        } catch (RuntimeException ignored) {
            detectorAvailable = false;
            flags = FastPathDetector.FLAG_NEEDS_SANITIZE | FastPathDetector.FLAG_NEEDS_MASK;
        }

        boolean shouldSanitize = shouldSanitize(flags, detectorAvailable);
        boolean shouldMask = shouldMask(flags, detectorAvailable);
        if (!shouldSanitize && !shouldMask) {
            return new ProcessResult(input, false, false, true);
        }

        String current = input;
        boolean sanitizedModified = false;
        boolean maskedModified = false;

        if (shouldSanitize) {
            String sanitized = strict ? sanitizer.sanitize(current) : sanitizer.sanitizeThrowable(current);
            sanitizedModified = !Objects.equals(current, sanitized);
            current = sanitized;
        }
        if (shouldMask) {
            String masked = masker.mask(current);
            maskedModified = !Objects.equals(current, masked);
            current = masked;
        }
        return new ProcessResult(current, sanitizedModified, maskedModified, false);
    }

    private boolean shouldSanitize(int flags, boolean detectorAvailable) {
        if (sanitizer == null) {
            return false;
        }
        if (!(sanitizer instanceof DefaultLogSanitizer)) {
            return true;
        }
        if (!detectorAvailable) {
            return true;
        }
        return (flags & FastPathDetector.FLAG_NEEDS_SANITIZE) != 0;
    }

    private boolean shouldMask(int flags, boolean detectorAvailable) {
        if (masker == null) {
            return false;
        }
        if (!(masker instanceof DefaultLogMasker)) {
            return true;
        }
        if (!detectorAvailable) {
            return true;
        }
        return (flags & FastPathDetector.FLAG_NEEDS_MASK) != 0;
    }

    static final class ProcessResult {
        private final String value;
        private final boolean sanitizedModified;
        private final boolean maskedModified;
        private final boolean fastPathHit;

        ProcessResult(String value, boolean sanitizedModified, boolean maskedModified, boolean fastPathHit) {
            this.value = value;
            this.sanitizedModified = sanitizedModified;
            this.maskedModified = maskedModified;
            this.fastPathHit = fastPathHit;
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

        boolean isFastPathHit() {
            return fastPathHit;
        }
    }
}
