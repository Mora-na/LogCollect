package com.logcollect.core.security;

import com.logcollect.core.test.ConcurrentTestHelper;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLogMaskerAdditionalTest extends CoreUnitTestBase {

    private static final String TIMEOUT_KEY = "logcollect.mask.timeout.ms";
    private String originalTimeout;
    private DefaultLogMasker masker;

    @BeforeEach
    void setUp() {
        originalTimeout = System.getProperty(TIMEOUT_KEY);
        masker = new DefaultLogMasker();
    }

    @AfterEach
    void tearDown() {
        if (originalTimeout == null) {
            System.clearProperty(TIMEOUT_KEY);
        } else {
            System.setProperty(TIMEOUT_KEY, originalTimeout);
        }
    }

    @Test
    void mask_11DigitsNotPhone_keptUnchanged() {
        String input = "编号10234567890";
        assertThat(masker.mask(input)).contains("10234567890");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "622202123456789",
            "6222021234567890",
            "62220212345678901",
            "622202123456789012",
            "6222021234567890123"
    })
    void mask_bankCardVariousLengths_masked(String card) {
        String masked = masker.mask(card);
        assertThat(masked).isNotEqualTo(card);
        assertThat(masked).contains("****");
    }

    @Test
    void mask_multiplePhones_allMasked() {
        String input = "A=13811111111,B=13922222222";
        String result = masker.mask(input);
        assertThat(result)
                .contains("138****1111")
                .contains("139****2222")
                .doesNotContain("13811111111")
                .doesNotContain("13922222222");
    }

    @Test
    void hasPotentialMatch_shortCircuitBranches() {
        assertThat(masker.hasPotentialMatch(null)).isFalse();
        assertThat(masker.hasPotentialMatch("")).isFalse();
        assertThat(masker.hasPotentialMatch("abcdef")).isFalse();
        assertThat(masker.hasPotentialMatch("user@email.com")).isTrue();
        assertThat(masker.hasPotentialMatch("phone=13800000000")).isTrue();
    }

    @Test
    void addRule_withUnsafeRegexString_returnsFalse() {
        boolean added = masker.addRule("(a+)+", m -> "***");
        assertThat(added).isFalse();
    }

    @Test
    void addRule_withNullInputs_returnsFalse() {
        assertThat(masker.addRule((Pattern) null, m -> "***")).isFalse();
        assertThat(masker.addRule(Pattern.compile("test"), null)).isFalse();
    }

    @Test
    void mask_shortContent_returnsOriginal() {
        assertThat(masker.mask("a@b.com")).isEqualTo("a@b.com");
    }

    @Test
    void mask_customRuleThrows_exceptionBranchReturnsOriginal() {
        masker.addRule(Pattern.compile("secret"), matcher -> {
            throw new IllegalStateException("boom");
        });
        String input = "secret=123";
        assertThat(masker.mask(input)).isEqualTo(input);
    }

    @Test
    void mask_interruptedThread_stillMasksContent() {
        Thread.currentThread().interrupt();
        try {
            String input = "phone=13812345678";
            assertThat(masker.mask(input)).contains("138****5678");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void addRule_withUnsafePattern_throwsIllegalArgument() {
        assertThatThrownBy(() -> masker.addRule(Pattern.compile("(a+)+"), matcher -> "***"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addRule_withSafeRegexString_returnsTrue() {
        assertThat(masker.addRule("(?i)password=\\w+", matcher -> "password=******")).isTrue();
        assertThat(masker.mask("password=secret123")).contains("password=******");
    }

    @Test
    void mask_ruleThrowsError_rethrowsOriginalError() {
        masker.addRule(Pattern.compile("boom12345"), matcher -> {
            throw new AssertionError("fatal-mask");
        });
        assertThatThrownBy(() -> masker.mask("boom12345"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fatal-mask");
    }

    @Test
    void mask_timeoutBranch_returnsOriginalAndIncrementsCounter() {
        DefaultLogMasker timeoutMasker = new DefaultLogMasker();
        timeoutMasker.addRule(Pattern.compile("secret"), matcher -> {
            throw new RegexTimeoutException("simulated-timeout");
        });

        String input = "secret=123";
        String result = timeoutMasker.mask(input);
        assertThat(result).isEqualTo(input);
        assertThat(timeoutMasker.getMaskTimeoutCount()).isGreaterThan(0L);
    }

    @Test
    void mask_concurrentCalls_stable() throws Exception {
        ConcurrentTestHelper.runConcurrently(8, () -> {
            for (int i = 0; i < 100; i++) {
                String result = masker.mask("phone=13812345678");
                assertThat(result).contains("138****5678");
            }
        }, Duration.ofSeconds(5));
    }

    @Test
    void readMaskTimeoutMs_privateMethod_branchesCovered() throws Exception {
        Method readMaskTimeout = DefaultLogMasker.class.getDeclaredMethod("readMaskTimeoutMs");
        readMaskTimeout.setAccessible(true);

        System.clearProperty(TIMEOUT_KEY);
        assertThat((Long) readMaskTimeout.invoke(masker)).isEqualTo(50L);

        System.setProperty(TIMEOUT_KEY, "-1");
        assertThat((Long) readMaskTimeout.invoke(masker)).isEqualTo(50L);

        System.setProperty(TIMEOUT_KEY, "abc");
        assertThat((Long) readMaskTimeout.invoke(masker)).isEqualTo(50L);

        System.setProperty(TIMEOUT_KEY, "5");
        assertThat((Long) readMaskTimeout.invoke(masker)).isEqualTo(5L);
    }

    @Test
    void builtinBankReplacer_shortLengthBranch_returnsStars() throws Exception {
        Field rulesField = DefaultLogMasker.class.getDeclaredField("rules");
        rulesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MaskRule> rules = (List<MaskRule>) rulesField.get(masker);
        MaskRule bankRule = rules.get(2);

        Field replacerField = MaskRule.class.getDeclaredField("replacer");
        replacerField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Function<Matcher, String> replacer = (Function<Matcher, String>) replacerField.get(bankRule);

        Matcher matcher = Pattern.compile("\\d{8}").matcher("12345678");
        assertThat(matcher.find()).isTrue();
        assertThat(replacer.apply(matcher)).isEqualTo("********");
    }

}
