package com.logcollect.core.pipeline;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.internal.LogCollectInternalLogger;

public class LogProcessingPipeline {
    private final LogSanitizer sanitizer;
    private final LogMasker masker;
    private final boolean sanitizeEnabled;
    private final boolean maskEnabled;
    private final MetricsBridge metrics;

    public interface MetricsBridge {
        void recordSanitizeHit();
        void recordMaskHit();
    }

    public LogProcessingPipeline(LogSanitizer sanitizer, LogMasker masker,
                                 boolean sanitizeEnabled, boolean maskEnabled,
                                 MetricsBridge metrics) {
        this.sanitizer = sanitizer;
        this.masker = masker;
        this.sanitizeEnabled = sanitizeEnabled;
        this.maskEnabled = maskEnabled;
        this.metrics = metrics;
    }

    public String process(String rawContent) {
        try {
            String result = rawContent;
            if (sanitizeEnabled && sanitizer != null) {
                String sanitized = sanitizer.sanitize(result);
                if (metrics != null && sanitized != null && !sanitized.equals(result)) {
                    metrics.recordSanitizeHit();
                }
                result = sanitized;
            }
            if (maskEnabled && masker != null) {
                String masked = masker.mask(result);
                if (metrics != null && masked != null && !masked.equals(result)) {
                    metrics.recordMaskHit();
                }
                result = masked;
            }
            return result;
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Security pipeline error, returning raw content", t);
            return rawContent;
        }
    }
}
