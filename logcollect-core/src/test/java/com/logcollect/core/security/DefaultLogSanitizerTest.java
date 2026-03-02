package com.logcollect.core.security;

import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DefaultLogSanitizerTest extends CoreUnitTestBase {

    private DefaultLogSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new DefaultLogSanitizer();
    }

    @Test
    void sanitize_nullInput_returnsNull() {
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    @Test
    void sanitize_emptyString_returnsEmpty() {
        assertThat(sanitizer.sanitize("")).isEmpty();
    }

    @Test
    void sanitize_normalText_unchanged() {
        String input = "用户登录成功 userId=12345";
        assertThat(sanitizer.sanitize(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "admin\n2026-01-01 INFO 伪造日志",
            "admin\r\n2026-01-01 INFO 伪造日志",
            "admin\r2026-01-01 INFO 伪造日志"
    })
    void sanitize_newlineInjection_replacedWithSpace(String input) {
        String result = sanitizer.sanitize(input);
        assertThat(result).doesNotContain("\n", "\r");
        assertThat(result).contains("admin");
    }

    @Test
    void sanitize_tabCharacter_replacedWithSpace() {
        String result = sanitizer.sanitize("key\tvalue");
        assertThat(result).doesNotContain("\t");
    }

    @ParameterizedTest
    @MethodSource("controlCharProvider")
    void sanitize_controlCharacters_removed(char controlChar, String description) {
        String input = "before" + controlChar + "after";
        String result = sanitizer.sanitize(input);
        assertThat(result)
                .as("控制字符 %s (U+%04X) 应被移除", description, (int) controlChar)
                .doesNotContain(String.valueOf(controlChar));
    }

    static Stream<Arguments> controlCharProvider() {
        return Stream.of(
                Arguments.of('\u0000', "NUL"),
                Arguments.of('\u0001', "SOH"),
                Arguments.of('\u0007', "BEL"),
                Arguments.of('\u0008', "BS"),
                Arguments.of('\u000B', "VT"),
                Arguments.of('\u000C', "FF"),
                Arguments.of('\u001B', "ESC"),
                Arguments.of('\u007F', "DEL")
        );
    }

    @Test
    void sanitize_htmlTags_removed() {
        String input = "用户<script>alert('xss')</script>登录";
        String result = sanitizer.sanitize(input);
        assertThat(result).doesNotContain("<script>", "</script>", "alert");
    }

    @Test
    void sanitize_ansiEscapeSequences_removed() {
        String input = "\u001B[31m红色文字\u001B[0m";
        String result = sanitizer.sanitize(input);
        assertThat(result).doesNotContain("\u001B[");
        assertThat(result).contains("红色文字");
    }

    @Test
    void sanitizeThrowable_nullInput_returnsNull() {
        assertThat(sanitizer.sanitizeThrowable(null)).isNull();
    }

    @Test
    void sanitizeThrowable_normalStackTrace_preservesNewlines() {
        String stackTrace = "java.lang.NullPointerException: msg\n"
                + "\tat com.example.Service.method(Service.java:42)\n"
                + "\tat com.example.Controller.handle(Controller.java:10)";
        String result = sanitizer.sanitizeThrowable(stackTrace);
        assertThat(result).contains("\n");
        assertThat(result).contains("\tat com.example.Service.method");
    }

    @Test
    void sanitizeThrowable_injectedFakeLine_markedAsExMsg() {
        String stackTrace = "java.lang.RuntimeException: evil\n"
                + "2026-01-01 INFO 伪造支付成功\n"
                + "\tat com.example.Service.method(Service.java:42)";
        String result = sanitizer.sanitizeThrowable(stackTrace);
        assertThat(result).contains("[ex-msg]");
        assertThat(result).contains("\tat com.example.Service.method");
    }

    @Test
    void sanitizeThrowable_htmlInException_removed() {
        String stackTrace = "RuntimeException: <img src=x onerror=alert(1)>\n"
                + "\tat com.example.A.b(A.java:1)";
        String result = sanitizer.sanitizeThrowable(stackTrace);
        assertThat(result).doesNotContain("<img", "onerror", "alert");
    }

    @Test
    void sanitizeWithStats_hitCount_incrementsOnModification() {
        com.logcollect.api.sanitizer.SanitizeResult stats = sanitizer.sanitizeWithStats("safe text");
        assertThat(stats.wasModified()).isFalse();
        assertThat(stats.getValue()).isEqualTo("safe text");

        com.logcollect.api.sanitizer.SanitizeResult stats2 = sanitizer.sanitizeWithStats("text\nwith\nnewlines");
        assertThat(stats2.wasModified()).isTrue();
    }

    @Test
    void sanitize_veryLongInput_handledWithoutOOM() {
        String longInput = repeat("a", 100_000);
        assertDoesNotThrow(() -> sanitizer.sanitize(longInput));
    }

    @Test
    void sanitize_unicodeEmoji_preserved() {
        String input = "处理成功 ✅🎉";
        assertThat(sanitizer.sanitize(input)).contains("✅", "🎉");
    }

    @Test
    void sanitize_chineseCharacters_preserved() {
        String input = "用户张三登录成功，手机号13812345678";
        String result = sanitizer.sanitize(input);
        assertThat(result).contains("用户张三登录成功");
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
