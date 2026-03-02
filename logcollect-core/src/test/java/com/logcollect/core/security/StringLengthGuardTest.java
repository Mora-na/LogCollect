package com.logcollect.core.security;

import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StringLengthGuardTest extends CoreUnitTestBase {

    @Test
    void guardContent_withinLimit_unchanged() {
        StringLengthGuard guard = new StringLengthGuard(100, 200);
        String input = "短消息";
        assertThat(guard.guardContent(input)).isEqualTo(input);
    }

    @Test
    void guardContent_exceedsLimit_truncatedWithMarker() {
        StringLengthGuard guard = new StringLengthGuard(20, 200);
        String input = repeat("a", 50);
        String result = guard.guardContent(input);
        assertThat(result).startsWith(repeat("a", 20));
        assertThat(result).contains("[truncated by LogCollect]");
    }

    @Test
    void guardContent_exactlyAtLimit_unchanged() {
        StringLengthGuard guard = new StringLengthGuard(10, 200);
        String input = "1234567890";
        assertThat(guard.guardContent(input)).isEqualTo(input);
    }

    @Test
    void guardContent_null_returnsNull() {
        StringLengthGuard guard = new StringLengthGuard(100, 200);
        assertThat(guard.guardContent(null)).isNull();
    }

    @Test
    void guardThrowable_exceedsLimit_truncated() {
        StringLengthGuard guard = new StringLengthGuard(100, 50);
        String longStack = "Exception: msg\n" + repeat("\tat line\n", 100);
        String result = guard.guardThrowable(longStack);
        assertThat(result).startsWith(longStack.substring(0, 50));
        assertThat(result).contains("[truncated by LogCollect]");
    }

    @Test
    void guardThrowable_null_returnsNull() {
        StringLengthGuard guard = new StringLengthGuard(100, 200);
        assertThat(guard.guardThrowable(null)).isNull();
    }

    @Test
    void defaultGuard_contentLimit32KB() {
        StringLengthGuard guard = StringLengthGuard.withDefaults();
        String content = repeat("x", 32768);
        assertThat(guard.guardContent(content)).hasSize(32768);

        String overLimit = repeat("x", 32769);
        assertThat(guard.guardContent(overLimit))
                .contains("[truncated by LogCollect]");
    }

    @Test
    void defaultGuard_throwableLimit64KB() {
        StringLengthGuard guard = StringLengthGuard.withDefaults();
        String stack = repeat("x", 65536);
        assertThat(guard.guardThrowable(stack)).hasSize(65536);
    }

    @Test
    void staticGuard_overloads_coverBothMaxLengthBranches() {
        String truncatedByDefault = StringLengthGuard.guardContent(repeat("x", 33000), 0);
        assertThat(truncatedByDefault).contains("[truncated by LogCollect]");

        String truncatedByCustom = StringLengthGuard.guardThrowable(repeat("y", 20), 5);
        assertThat(truncatedByCustom).startsWith("yyyyy");
        assertThat(truncatedByCustom).contains("[truncated by LogCollect]");
    }

    @Test
    void constructor_zeroLimit_throws() {
        assertThatThrownBy(() -> new StringLengthGuard(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_negativeLimit_throws() {
        assertThatThrownBy(() -> new StringLengthGuard(-1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nonPositiveThrowableLimit_throws() {
        assertThatThrownBy(() -> new StringLengthGuard(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
