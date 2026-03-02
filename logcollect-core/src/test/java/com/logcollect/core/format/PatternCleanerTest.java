package com.logcollect.core.format;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PatternCleanerTest {

    @ParameterizedTest
    @CsvSource({
            "'%d{yyyy-MM-dd} %p %m%n','%d{yyyy-MM-dd} %p %m%n'",
            "'%clr(%d{yyyy-MM-dd}){faint} %m','%d{yyyy-MM-dd} %m'",
            "'%highlight(%-5level) %style(%msg){red}','%-5level %msg'",
            "'${LOG_DATEFORMAT_PATTERN:-yyyy-MM-dd} --- %msg','yyyy-MM-dd %msg'"
    })
    void clean_variousPatterns(String input, String expected) {
        assertThat(PatternCleaner.clean(input)).isEqualTo(expected);
    }
}
