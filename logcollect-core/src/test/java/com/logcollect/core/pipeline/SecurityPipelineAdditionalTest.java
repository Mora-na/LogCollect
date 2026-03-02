package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogEntry;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SecurityPipelineAdditionalTest extends CoreUnitTestBase {

    @Test
    void process_nullGuard_skipsLengthGuardButStillWorks() {
        SecurityPipeline pipeline = new SecurityPipeline(
                new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        String content = repeat("x", 50_000);
        LogEntry safe = pipeline.process(createTestEntry(content, "INFO"));
        assertThat(safe).isNotNull();
        assertThat(safe.getContent()).isNotNull();
    }

    @Test
    void process_minimalEntryWithNullFields_noNpe() {
        SecurityPipeline pipeline = new SecurityPipeline(
                new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        LogEntry raw = LogEntry.builder()
                .traceId("t")
                .content(null)
                .level("INFO")
                .timestamp(0)
                .threadName(null)
                .loggerName(null)
                .throwableString(null)
                .build();
        assertDoesNotThrow(() -> pipeline.process(raw));
    }

    @Test
    void process_withoutThrowable_keepsThrowableNull() {
        SecurityPipeline pipeline = new SecurityPipeline(
                new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        LogEntry safe = pipeline.process(createTestEntry("msg", "INFO"));
        assertThat(safe.getThrowableString()).isNull();
    }

    @Test
    void process_withMetricsAndMdcKeySanitization_hitsAllCallbacks() {
        SecurityPipeline pipeline = new SecurityPipeline(
                new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        AtomicInteger callbacks = new AtomicInteger(0);

        Map<String, String> mdc = new LinkedHashMap<String, String>();
        mdc.put("bad key?!", "phone=13812345678");
        mdc.put(repeat("k", 140), "value");
        mdc.put("", "ignored");

        LogEntry raw = LogEntry.builder()
                .traceId("t")
                .content("line1\nline2 phone=13812345678")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("c")
                .throwableString("Exception: phone=13812345678\n\tat com.A.b(A.java:1)")
                .mdcContext(mdc)
                .build();

        SecurityPipeline.SecurityMetrics metrics = new SecurityPipeline.SecurityMetrics() {
            @Override
            public void onContentSanitized() {
                callbacks.incrementAndGet();
            }

            @Override
            public void onThrowableSanitized() {
                callbacks.incrementAndGet();
            }

            @Override
            public void onContentMasked() {
                callbacks.incrementAndGet();
            }

            @Override
            public void onThrowableMasked() {
                callbacks.incrementAndGet();
            }
        };

        LogEntry safe = pipeline.process(raw, metrics);
        assertThat(callbacks.get()).isGreaterThan(0);
        assertThat(safe.getMdcContext()).isNotEmpty();
        assertThat(safe.getMdcContext().keySet().iterator().next()).doesNotContain(" ");
        assertThat(safe.getMdcContext()).allSatisfy((k, v) -> assertThat(k.length()).isLessThanOrEqualTo(128));
    }

    @Test
    void securityMetrics_defaultMethods_callable() {
        SecurityPipeline.SecurityMetrics metrics = new SecurityPipeline.SecurityMetrics() {
        };
        metrics.onContentSanitized();
        metrics.onThrowableSanitized();
        metrics.onContentMasked();
        metrics.onThrowableMasked();
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
