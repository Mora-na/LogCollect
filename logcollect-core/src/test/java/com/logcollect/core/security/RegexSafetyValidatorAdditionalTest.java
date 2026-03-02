package com.logcollect.core.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RegexSafetyValidatorAdditionalTest {

    @Test
    void isSafe_basicBranches() {
        assertThat(RegexSafetyValidator.isSafe(null)).isFalse();
        assertThat(RegexSafetyValidator.isSafe("")).isTrue();
        assertThat(RegexSafetyValidator.isSafe(repeat("a", 600))).isFalse();
        assertThat(RegexSafetyValidator.isSafe("[abc")).isFalse();
    }

    @Test
    void isSafe_grayZonePattern_safeResult() {
        assertThat(RegexSafetyValidator.isSafe("([ab]|a)*")).isTrue();
    }

    @Test
    void safeCompile_branches() {
        assertThat(RegexSafetyValidator.safeCompile("1[3-9]\\d{9}")).isNotNull();
        assertThat(RegexSafetyValidator.safeCompile("(a+)+")).isNull();
        assertThat(RegexSafetyValidator.safeCompile("[abc")).isNull();
    }

    @Test
    void validate_withEmptyRegex_passes() {
        assertDoesNotThrow(() -> RegexSafetyValidator.validate(java.util.regex.Pattern.compile("")));
    }

    @Test
    void validate_nullPattern_throwsNpe() {
        assertThatThrownBy(() -> RegexSafetyValidator.validate((Pattern) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void isSafe_interruptedBranch_returnsFalse() {
        Thread.currentThread().interrupt();
        try {
            assertThat(RegexSafetyValidator.isSafe("a+")).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void safeCompile_nullRegex_returnsNull() {
        assertThat(RegexSafetyValidator.safeCompile(null)).isNull();
    }

    @Test
    void isSafe_timeoutBranch_returnsFalse() throws Exception {
        Field executorField = RegexSafetyValidator.class.getDeclaredField("EXECUTOR");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(null);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(RegexSafetyValidator.isSafe("a+")).isFalse();
        } finally {
            release.countDown();
        }
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
