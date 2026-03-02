package com.logcollect.core.security;

import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DefaultLogMaskerTest extends CoreUnitTestBase {

    private DefaultLogMasker masker;

    @BeforeEach
    void setUp() {
        masker = new DefaultLogMasker();
    }

    @ParameterizedTest
    @CsvSource({
            "13812345678,138****5678",
            "15999998888,159****8888",
            "18600001111,186****1111"
    })
    void mask_phoneNumber_middleMasked(String input, String expected) {
        assertThat(masker.mask(input)).isEqualTo(expected);
    }

    @Test
    void mask_phoneInContext_onlyPhoneMasked() {
        String input = "用户手机: 13812345678, 请联系";
        String result = masker.mask(input);
        assertThat(result).contains("138****5678");
        assertThat(result).contains("用户手机:");
    }

    @Test
    void mask_phoneEmbeddedInAsciiWord_notMasked() {
        String input = "tokenA13812345678B";
        String result = masker.mask(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    void mask_idCard18_keepFirst6Last4() {
        String result = masker.mask("110105199001011234");
        assertThat(result).isEqualTo("110105********1234");
    }

    @Test
    void mask_idCardWithX_keepFirst6Last4() {
        String result = masker.mask("11010519900101123X");
        assertThat(result).isEqualTo("110105********123X");
    }

    @Test
    void mask_idCardInChineseContext_masked() {
        String result = masker.mask("用户身份证110105199001011234已登记");
        assertThat(result).contains("110105********1234");
    }

    @Test
    void mask_bankCard_keepFirst4Last4() {
        String result = masker.mask("6222021234567890123");
        assertThat(result).isEqualTo("6222****0123");
    }

    @Test
    void mask_bankCardInChineseContext_masked() {
        String result = masker.mask("银行卡6222021234567890123已绑定");
        assertThat(result).contains("6222****0123");
    }

    @Test
    void mask_email_keepFirst2() {
        String result = masker.mask("zhangsan@example.com");
        assertThat(result).isEqualTo("zh****@example.com");
    }

    @Test
    void mask_shortEmail_handleGracefully() {
        String result = masker.mask("a@b.com");
        assertThat(result).isEqualTo("a@b.com");
    }

    @Test
    void mask_multiplePatterns_allMasked() {
        String input = "用户13812345678的身份证110105199001011234，邮箱zhangsan@example.com";
        String result = masker.mask(input);
        assertThat(result)
                .contains("138****5678")
                .contains("110105********1234")
                .contains("zh****@example.com");
    }

    @Test
    void addRule_customPattern_applied() {
        masker.addRule(
                Pattern.compile("(?i)password[=:]\\S+"),
                m -> m.group().replaceAll("([=:])\\S+", "$1******")
        );
        String result = masker.mask("password=secret123");
        assertThat(result).contains("password=******");
    }

    @Test
    void addRule_redosPattern_rejected() {
        assertThatThrownBy(() ->
                masker.addRule(Pattern.compile("(a+)+"), m -> "***"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ReDoS");
    }

    @Test
    void mask_null_returnsNull() {
        assertThat(masker.mask(null)).isNull();
    }

    @Test
    void mask_empty_returnsEmpty() {
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void mask_noSensitiveData_unchanged() {
        String input = "普通日志信息，没有敏感数据";
        assertThat(masker.mask(input)).isEqualTo(input);
    }

    @Test
    void mask_extremelyLongInput_completesWithinTimeout() {
        String input = repeat("1381234567", 10_000) + "8";
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> masker.mask(input));
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
