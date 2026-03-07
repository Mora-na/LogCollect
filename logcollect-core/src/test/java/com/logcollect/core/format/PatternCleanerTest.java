package com.logcollect.core.format;

import org.junit.jupiter.api.Test;
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

    @Test
    void clean_removesColorConverters_parenthesisSyntax() {
        String input = "%cyan([%thread]) %magenta(%logger{36} - %msg%n)";
        String expected = "[%thread] %logger{36} - %msg%n";
        assertThat(PatternCleaner.clean(input)).isEqualTo(expected);
    }

    @Test
    void clean_removesColorConverters_braceSyntax() {
        String input = "%red{%msg} %blue{%level} %highlight{%msg}";
        String expected = "%msg %level %msg";
        assertThat(PatternCleaner.clean(input)).isEqualTo(expected);
    }

    @Test
    void clean_removesConsoleStylePadReplaceConverters() {
        String input = "%style(%msg){bold} %pad(%-5level){10} %replace(%msg){\\s+}{_}";
        String expected = "%msg %-5level %msg";
        assertThat(PatternCleaner.clean(input)).isEqualTo(expected);
    }

    @Test
    void clean_removesReplaceConverter_braceSyntax() {
        String input = "%replace{%msg}{\\s+}{_}";
        String expected = "%msg";
        assertThat(PatternCleaner.clean(input)).isEqualTo(expected);
    }

    @Test
    void clean_removesUnderlineAndBoldConverters() {
        String input = "%underline(%msg) %bold{%msg}";
        String expected = "%msg %msg";
        assertThat(PatternCleaner.clean(input)).isEqualTo(expected);
    }
}
