package com.logcollect.core.format;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatternValidatorTest {

    @Test
    void validate_validPattern_passes() {
        assertThat(PatternValidator.validateAndClean("%d{yyyy-MM-dd HH:mm:ss} %p %m%n"))
                .isEqualTo("%d{yyyy-MM-dd HH:mm:ss} %p %m%n");
    }

    @Test
    void validate_invalidPattern_throws() {
        assertThatThrownBy(() -> PatternValidator.validateAndClean("%Z{invalid}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_tooLongPattern_throws() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append('x');
        }
        assertThatThrownBy(() -> PatternValidator.validateAndClean(sb.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
