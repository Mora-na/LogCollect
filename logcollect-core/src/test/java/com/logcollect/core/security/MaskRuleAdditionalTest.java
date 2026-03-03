package com.logcollect.core.security;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MaskRuleAdditionalTest {

    @Test
    void apply_withNullContent_returnsNull() {
        MaskRule rule = new MaskRule(Pattern.compile("secret=\\w+"), matcher -> "secret=***");
        assertThat(rule.apply(null)).isNull();
    }

    @Test
    void apply_withEmptyContent_returnsEmpty() {
        MaskRule rule = new MaskRule(Pattern.compile("secret=\\w+"), matcher -> "secret=***");
        assertThat(rule.apply("")).isEmpty();
    }

    @Test
    void apply_withPreCheckNotMatched_returnsOriginal() {
        MaskRule rule = new MaskRule(
                Pattern.compile("secret=\\w+"),
                matcher -> "secret=***",
                "z");
        String input = "secret=abc";
        assertThat(rule.apply(input)).isEqualTo(input);
    }
}
