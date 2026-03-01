package com.logcollect.core.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class QuickSanitizerTest {

    @Test
    void shouldRemoveControlCharsAndTruncate() {
        String input = "abc\r\n\t" + repeat("x", 300);
        String summary = QuickSanitizer.summarize(input, 256);

        Assertions.assertFalse(summary.contains("\r"));
        Assertions.assertFalse(summary.contains("\n"));
        Assertions.assertFalse(summary.contains("\t"));
        Assertions.assertEquals(256, summary.length());
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
