package com.logcollect.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickSanitizerAdditionalTest {

    @Test
    void removeControlChars_nullAndEmpty_safe() {
        assertThat(QuickSanitizer.removeControlChars(null)).isNull();
        assertThat(QuickSanitizer.removeControlChars("")).isEmpty();
    }

    @Test
    void summarize_nullAndDefaultLimitBranches() {
        assertThat(QuickSanitizer.summarize(null, 128)).isNull();
        assertThat(QuickSanitizer.summarize("abc", -1)).isEqualTo("abc");
    }

    @Test
    void summarize_whenTooLong_truncatesWithConfiguredLimit() {
        String text = repeat("x", 1000);
        assertThat(QuickSanitizer.summarize(text, 64)).hasSize(64);
        assertThat(QuickSanitizer.summarize(text, 0)).hasSize(256);
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
