package com.logcollect.core.security;

import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLogSanitizerAdditionalTest extends CoreUnitTestBase {

    private DefaultLogSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new DefaultLogSanitizer();
    }

    @Test
    void sanitizeThrowable_emptyString_returnsEmpty() {
        assertThat(sanitizer.sanitizeThrowable("")).isEmpty();
    }

    @Test
    void sanitizeThrowable_safeSingleLine_returnsOriginal() {
        String input = "RuntimeException: safe text";
        assertThat(sanitizer.sanitizeThrowable(input)).isEqualTo(input);
    }

    @Test
    void sanitizeThrowable_withAnsiAndDangerousControl_noLineBreak_returnsCleanSingleLine() {
        String input = "prefix\u001B[31msecret\u0001\u001B[0m";
        String output = sanitizer.sanitizeThrowable(input);
        assertThat(output).isEqualTo("prefixsecret");
    }

    @Test
    void sanitizeThrowable_withInvalidAnsiSequence_keepsOriginal() {
        String input = "prefix\u001B[31!boom";
        String output = sanitizer.sanitizeThrowable(input);
        assertThat(output).doesNotContain("\u001B");
        assertThat(output).isEqualTo("prefix[31!boom");
    }

    @Test
    void sanitizeThrowable_withUnfinishedAnsiSequence_keepsOriginal() {
        String input = "prefix\u001B[31";
        String output = sanitizer.sanitizeThrowable(input);
        assertThat(output).doesNotContain("\u001B");
        assertThat(output).isEqualTo("prefix[31");
    }

    @Test
    void sanitizeThrowable_withAllRiskMarkers_triggersCombinedCleaning() {
        String input = "\u001B[31m<script>alert(1)</script>\u0001\nsafe-line";
        String output = sanitizer.sanitizeThrowable(input);
        assertThat(output).doesNotContain("\u001B[", "<script>", "alert", "\u0001");
        assertThat(output).contains("safe-line");
    }

    @Test
    void sanitizeThrowable_standardStackLinesStayUnmarked_butInjectedLineIsMarked() {
        String input = "java.lang.RuntimeException: boom\n"
                + "Caused by: java.lang.IllegalStateException\n"
                + "Suppressed: java.io.IOException\n"
                + "\tat demo.Service.call(Service.java:12)   \n"
                + "... 12 more\n"
                + "... 3 common frames omitted\n"
                + "injected line";
        String output = sanitizer.sanitizeThrowable(input);

        assertThat(output).contains("Caused by: java.lang.IllegalStateException");
        assertThat(output).contains("Suppressed: java.io.IOException");
        assertThat(output).contains("... 12 more");
        assertThat(output).contains("... 3 common frames omitted");
        assertThat(output).doesNotContain("[ex-msg] Caused by:");
        assertThat(output).doesNotContain("[ex-msg] Suppressed:");
        assertThat(output).doesNotContain("[ex-msg] ... 12 more");
        assertThat(output).doesNotContain("[ex-msg] ... 3 common frames omitted");
        assertThat(output).contains("[ex-msg] injected line");
    }
}
