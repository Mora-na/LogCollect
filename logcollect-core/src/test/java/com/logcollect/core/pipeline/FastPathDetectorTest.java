package com.logcollect.core.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FastPathDetectorTest {

    @Test
    void scan_asciiAlnum_onlyFastPath() {
        int flags = FastPathDetector.scan("orderId-001 userA ok");
        assertThat(flags).isZero();
    }

    @Test
    void scan_withControlChar_needsSanitize() {
        int flags = FastPathDetector.scan("hello\u0000world");
        assertThat(flags & FastPathDetector.FLAG_NEEDS_SANITIZE).isNotZero();
    }

    @Test
    void scan_with11Digits_needsMask() {
        int flags = FastPathDetector.scan("phone=13812345678");
        assertThat(flags & FastPathDetector.FLAG_NEEDS_MASK).isNotZero();
    }

    @Test
    void scan_withAt_needsMask() {
        int flags = FastPathDetector.scan("mail=a@example.com");
        assertThat(flags & FastPathDetector.FLAG_NEEDS_MASK).isNotZero();
    }

    @Test
    void scan_mixed_needsBoth() {
        int flags = FastPathDetector.scan("x<y phone=13812345678");
        assertThat(flags).isEqualTo(FastPathDetector.FLAG_NEEDS_SANITIZE | FastPathDetector.FLAG_NEEDS_MASK);
    }

    @Test
    void scan_nullAndEmpty_returnsZero() {
        assertThat(FastPathDetector.scan(null)).isZero();
        assertThat(FastPathDetector.scan("")).isZero();
    }

    @Test
    void scanThrowable_withLineBreak_requiresSanitize() {
        int flags = FastPathDetector.scanThrowable("Exception: x\n\tat com.A.b(A.java:1)");
        assertThat(flags & FastPathDetector.FLAG_NEEDS_SANITIZE).isNotZero();
    }

    @Test
    void scan_longString_completes() {
        StringBuilder sb = new StringBuilder(100_000);
        for (int i = 0; i < 100_000; i++) {
            sb.append('a');
        }
        String longText = sb.toString();
        assertTimeoutPreemptively(Duration.ofMillis(50), () -> {
            int flags = FastPathDetector.scan(longText);
            assertThat(flags).isZero();
        });
    }
}
