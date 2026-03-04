package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogEntry;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPipelineCoverageTest extends CoreUnitTestBase {

    @Test
    void processRawRecord_withSafeMdcAndNullMetrics_reusesInputReference() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        Map<String, String> mdc = new LinkedHashMap<String, String>();
        mdc.put("_logCollect_traceId", "trace-1");
        mdc.put("userId", "safeValue");

        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                "t", "message", "INFO", 123L, "main", "demo.Logger", "RuntimeException: safe",
                mdc, null);

        assertThat(record.getMdcContext()).isSameAs(mdc);
        assertThat(record.getMdcContext()).containsEntry("_logCollect_traceId", "trace-1");
    }

    @Test
    void processRawRecord_estimateBytes_countsAllFieldsAndMdc() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        Map<String, String> mdc = new LinkedHashMap<String, String>();
        mdc.put("k1", "v1");
        mdc.put("k2", "v2");

        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                "t", "msg", "INFO", 1L, "th", "lg", "ex", mdc, SecurityPipeline.SecurityMetrics.NOOP);

        long expected = 112L
                + estimateString("t")
                + estimateString("msg")
                + estimateString("INFO")
                + estimateString("th")
                + estimateString("lg")
                + estimateString("ex")
                + 64L
                + 32L + estimateString("k1") + estimateString("v1")
                + 32L + estimateString("k2") + estimateString("v2");

        assertThat(record.estimateBytes()).isEqualTo(expected);

        LogEntry logEntry = record.toLogEntry();
        assertThat(logEntry.getTraceId()).isEqualTo("t");
        assertThat(logEntry.getContent()).isEqualTo("msg");
        assertThat(logEntry.getMdcContext()).containsEntry("k1", "v1");
    }

    @Test
    void processRawRecord_nullStringFields_estimateBytesFallsBackToBaseSize() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker(), null);

        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                null, null, null, 0L, null, null, null, Collections.<String, String>emptyMap(),
                SecurityPipeline.SecurityMetrics.NOOP);

        assertThat(record.estimateBytes()).isEqualTo(112L);
        assertThat(record.toLogEntry().getMdcContext()).isEmpty();
    }

    @Test
    void processRawRecord_nullMdcKey_isDroppedFromResult() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        Map<String, String> mdc = new LinkedHashMap<String, String>();
        mdc.put(null, "bad");
        mdc.put("safe", "ok");

        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                "t", "msg", "INFO", 1L, "th", "lg", null, mdc, SecurityPipeline.SecurityMetrics.NOOP);

        assertThat(record.getMdcContext()).containsKey("safe");
        assertThat(record.getMdcContext()).doesNotContainKey(null);
    }

    @Test
    void process_safeThrowable_triggersThrowableFastPathMetric() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker(), null);
        AtomicInteger fastPathHits = new AtomicInteger(0);
        SecurityPipeline.SecurityMetrics metrics = new SecurityPipeline.SecurityMetrics() {
            @Override
            public void onFastPathHit() {
                fastPathHits.incrementAndGet();
            }
        };

        LogEntry raw = LogEntry.builder()
                .traceId("t")
                .content("line1\nline2")
                .level("INFO")
                .timestamp(1L)
                .threadName("th")
                .loggerName("lg")
                .throwableString("RuntimeException: safe")
                .build();

        pipeline.process(raw, metrics);
        assertThat(fastPathHits.get()).isEqualTo(1);
    }

    private static long estimateString(String value) {
        return 48L + ((long) value.length() << 1);
    }
}
