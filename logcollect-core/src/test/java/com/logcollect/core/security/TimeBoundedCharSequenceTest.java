package com.logcollect.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeBoundedCharSequenceTest {

    @Test
    void charAt_beforeTimeout_returnsDelegateChar() {
        TimeBoundedCharSequence bounded = new TimeBoundedCharSequence("abc", 1000L);
        assertThat(bounded.charAt(1)).isEqualTo('b');
    }

    @Test
    void charAt_afterDeadline_throwsTimeoutException() throws Exception {
        TimeBoundedCharSequence bounded = new TimeBoundedCharSequence("abcdef", 1L);
        Thread.sleep(5L);
        for (int i = 0; i < 63; i++) {
            bounded.charAt(0);
        }
        assertThatThrownBy(() -> bounded.charAt(0))
                .isInstanceOf(RegexTimeoutException.class);
    }

    @Test
    void subSequence_inheritsDeadline() throws Exception {
        TimeBoundedCharSequence bounded = new TimeBoundedCharSequence("abcdef", 1L);
        CharSequence sub = bounded.subSequence(0, 3);
        Thread.sleep(5L);
        for (int i = 0; i < 63; i++) {
            sub.charAt(0);
        }
        assertThatThrownBy(() -> sub.charAt(0))
                .isInstanceOf(RegexTimeoutException.class);
    }
}
