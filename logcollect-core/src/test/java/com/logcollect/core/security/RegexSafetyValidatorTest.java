package com.logcollect.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RegexSafetyValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "1[3-9]\\d{9}",
            "\\d{17}[\\dXx]",
            "[\\w.+-]+@[\\w.-]+\\.\\w{2,}",
            "\\d{15,19}"
    })
    void validate_safePatterns_pass(String pattern) {
        assertDoesNotThrow(() -> RegexSafetyValidator.validate(Pattern.compile(pattern)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(a+)+",
            "(a+)*",
            "([a-zA-Z]+)*"
    })
    void validate_redosPatterns_rejected(String pattern) {
        assertThatThrownBy(() -> RegexSafetyValidator.validate(Pattern.compile(pattern)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ReDoS");
    }

    @Test
    void validate_null_throws() {
        assertThatThrownBy(() -> RegexSafetyValidator.validate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void validate_emptyPattern_passes() {
        assertDoesNotThrow(() -> RegexSafetyValidator.validate(Pattern.compile("")));
    }

    @Test
    void safeCompile_unsafeRegex_returnsNull() {
        assertThat(RegexSafetyValidator.safeCompile("(a+)+")).isNull();
    }
}
