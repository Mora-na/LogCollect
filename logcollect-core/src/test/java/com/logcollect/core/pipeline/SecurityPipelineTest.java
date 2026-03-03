package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogEntry;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.StringLengthGuard;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPipelineTest extends CoreUnitTestBase {

    private SecurityPipeline pipeline;
    private DefaultLogSanitizer sanitizer;
    private DefaultLogMasker masker;
    private StringLengthGuard guard;

    @BeforeEach
    void setUp() {
        sanitizer = new DefaultLogSanitizer();
        masker = new DefaultLogMasker();
        guard = StringLengthGuard.withDefaults();
        pipeline = new SecurityPipeline(sanitizer, masker, guard);
    }

    @Test
    void process_nullEntry_returnsNull() {
        assertThat(pipeline.process(null)).isNull();
    }

    @Test
    void process_normalEntry_allFieldsSafe() {
        LogEntry raw = createTestEntry("用户登录成功", "INFO");
        LogEntry safe = pipeline.process(raw);

        assertThat(safe.getContent()).isEqualTo("用户登录成功");
        assertThat(safe.getLevel()).isEqualTo("INFO");
        assertThat(safe.getThreadName()).isNotNull();
        assertThat(safe.getLoggerName()).isNotNull();
    }

    @Test
    void process_contentWithInjection_sanitized() {
        LogEntry raw = createTestEntry("admin\n2026-01-01 INFO 伪造", "INFO");
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getContent()).doesNotContain("\n", "\r");
    }

    @Test
    void process_contentWithPhone_masked() {
        LogEntry raw = createTestEntry("手机13812345678", "INFO");
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getContent()).contains("138****5678");
        assertThat(safe.getContent()).doesNotContain("13812345678");
    }

    @Test
    void process_throwableWithInjection_sanitizedButNewlinesPreserved() {
        LogEntry raw = createTestEntryWithThrowable(
                "error occurred",
                "Exception: msg\n\tat com.A.b(A.java:1)\nINFO fake log\n\tat com.C.d(C.java:2)");
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getThrowableString()).contains("\tat com.A.b");
        assertThat(safe.getThrowableString()).contains("[ex-msg]");
        assertThat(safe.getThrowableString()).contains("\n");
    }

    @Test
    void process_throwableWithPhone_masked() {
        LogEntry raw = createTestEntryWithThrowable(
                "error",
                "Exception: phone=13812345678\n\tat com.A.b(A.java:1)");
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getThrowableString()).contains("138****5678");
    }

    @Test
    void process_threadNameWithControlChars_trustedSourcePassthrough() {
        LogEntry raw = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("pool-1\u0000-thread-1")
                .loggerName("com.test.A")
                .build();
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getThreadName()).contains("\u0000");
    }

    @Test
    void process_loggerNameWithHtml_trustedSourcePassthrough() {
        LogEntry raw = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.test.<script>A</script>")
                .build();
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getLoggerName()).contains("<script>", "</script>");
    }

    @Test
    void process_mdcValueWithInjection_sanitized() {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("requestId", "evil\n2026-01-01 INFO 伪造支付");
        LogEntry raw = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.test.A")
                .mdcContext(mdc)
                .build();
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getMdcContext().get("requestId")).doesNotContain("\n");
    }

    @Test
    void process_mdcValueWithPhone_masked() {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("userId", "phone=13812345678");
        LogEntry raw = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.test.A")
                .mdcContext(mdc)
                .build();
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getMdcContext().get("userId")).contains("138****5678");
    }

    @Test
    void process_frameworkMdcKey_valueTrustedAndNotSanitized() {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("_logCollect_traceId", "trace\nraw");
        LogEntry raw = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.test.A")
                .mdcContext(mdc)
                .build();
        LogEntry safe = pipeline.process(raw);
        assertThat(safe.getMdcContext().get("_logCollect_traceId")).isEqualTo("trace\nraw");
    }

    @Test
    void process_emptyMdc_returnsEmptyImmutableMap() {
        LogEntry raw = LogEntry.builder()
                .traceId("t2")
                .content("ok")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .mdcContext(Collections.emptyMap())
                .build();
        LogEntry out = pipeline.process(raw);
        assertThat(out.getMdcContext()).isEmpty();
    }

    @Test
    void process_overlyLongContent_truncated() {
        SecurityPipeline strictPipeline = new SecurityPipeline(sanitizer, masker, new StringLengthGuard(100, 200));
        String longContent = repeat("x", 1000);
        LogEntry raw = createTestEntry(longContent, "INFO");
        LogEntry safe = strictPipeline.process(raw);
        assertThat(safe.getContent().length()).isLessThan(longContent.length());
        assertThat(safe.getContent()).contains("[truncated by LogCollect]");
    }

    @Test
    void process_sanitizeDisabled_noSanitizationButMaskingStillWorks() {
        SecurityPipeline noSanitize = new SecurityPipeline(null, masker, guard);
        LogEntry raw = createTestEntry("phone=13812345678\ninjection", "INFO");
        LogEntry safe = noSanitize.process(raw);
        assertThat(safe.getContent()).contains("\n");
        assertThat(safe.getContent()).contains("138****5678");
    }

    @Test
    void process_maskDisabled_noMasking() {
        SecurityPipeline noMask = new SecurityPipeline(sanitizer, null, guard);
        LogEntry raw = createTestEntry("phone=13812345678", "INFO");
        LogEntry safe = noMask.process(raw);
        assertThat(safe.getContent()).contains("13812345678");
    }

    @Test
    void process_originalEntry_notModified() {
        LogEntry raw = createTestEntry("phone=13812345678\ninjection", "INFO");
        String originalContent = raw.getContent();
        pipeline.process(raw);
        assertThat(raw.getContent()).isEqualTo(originalContent);
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
