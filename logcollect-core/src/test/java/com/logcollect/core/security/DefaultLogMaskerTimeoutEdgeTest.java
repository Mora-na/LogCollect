package com.logcollect.core.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLogMaskerTimeoutEdgeTest {

    @Test
    void mask_regexTimeoutExceptionBranch_returnsOriginalAndIncrementsCounter() {
        DefaultLogMasker masker = new DefaultLogMasker();
        masker.addRule(Pattern.compile("secret"), matcher -> {
            throw new RegexTimeoutException("simulated-timeout");
        });

        String input = "secret=123";
        String result = masker.mask(input);

        assertThat(result).isEqualTo(input);
        assertThat(masker.getMaskTimeoutCount()).isEqualTo(1L);
    }

    @Test
    void mask_noMaskExecutorThreadCreated() {
        DefaultLogMasker masker = new DefaultLogMasker();
        String input = "phone=13812345678";

        for (int i = 0; i < 10_000; i++) {
            masker.mask(input);
        }

        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        boolean found = false;
        for (Thread thread : threads) {
            if (thread.getName().startsWith("logcollect-mask-executor")) {
                found = true;
                break;
            }
        }
        assertThat(found).isFalse();
    }
}
