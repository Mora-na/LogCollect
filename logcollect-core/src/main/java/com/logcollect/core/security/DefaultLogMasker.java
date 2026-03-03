package com.logcollect.core.security;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认脱敏器实现。
 *
 * <p>内置手机号、身份证号、银行卡号、邮箱等规则，并支持运行时追加自定义规则。
 * 正则执行超时保护由 {@link TimeBoundedCharSequence} 在引擎内部实现。
 */
public class DefaultLogMasker implements LogMasker {
    private static final long DEFAULT_MASK_TIMEOUT_MS = 50L;

    private final List<MaskRule> rules = new CopyOnWriteArrayList<MaskRule>();
    private final long maskTimeoutMs;
    private final AtomicLong maskTimeoutCounter = new AtomicLong(0);

    /**
     * 创建默认脱敏器并注册内置规则。
     */
    public DefaultLogMasker() {
        this.maskTimeoutMs = readMaskTimeoutMs();
        addBuiltinRules();
    }

    private void addBuiltinRules() {
        Pattern phone = Pattern.compile("(?<![0-9A-Za-z_])1[3-9]\\d{9}(?![0-9A-Za-z_])");
        rules.add(new MaskRule(phone, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                return v.substring(0, 3) + "****" + v.substring(7);
            }
        }, "1"));

        Pattern id = Pattern.compile("(?<![0-9A-Za-z_])\\d{17}[0-9Xx](?![0-9A-Za-z_])");
        rules.add(new MaskRule(id, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                return v.substring(0, 6) + "********" + v.substring(14);
            }
        }));

        Pattern bank = Pattern.compile("(?<![0-9A-Za-z_])\\d{12,19}(?![0-9A-Za-z_])");
        rules.add(new MaskRule(bank, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                if (v.length() <= 8) {
                    return "********";
                }
                return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
            }
        }));

        Pattern email = Pattern.compile("([\\w._%+-]+)@([\\w.-]+\\.[A-Za-z]{2,})");
        rules.add(new MaskRule(email, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String user = m.group(1);
                String domain = m.group(2);
                String masked = user.length() <= 2 ? "**" : user.substring(0, 2) + "****";
                return masked + "@" + domain;
            }
        }, "@"));
    }

    /**
     * 添加一条自定义脱敏规则。
     *
     * @param regex    正则表达式
     * @param replacer 替换函数
     * @return true 表示规则添加成功
     */
    public boolean addRule(String regex, Function<Matcher, String> replacer) {
        Pattern pattern = RegexSafetyValidator.safeCompile(regex);
        if (pattern == null) {
            return false;
        }
        return addRule(pattern, replacer);
    }

    /**
     * 添加一条自定义脱敏规则。
     *
     * @param pattern  正则 Pattern（建议调用方自行预编译）
     * @param replacer 替换函数
     * @return true 表示规则添加成功
     */
    public boolean addRule(Pattern pattern, Function<Matcher, String> replacer) {
        if (pattern == null || replacer == null) {
            return false;
        }
        RegexSafetyValidator.validate(pattern);
        rules.add(new MaskRule(pattern, replacer));
        return true;
    }

    @Override
    public String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 热路径短路：长度过短不可能是常见敏感信息。
        if (content.length() < 8) {
            return content;
        }
        if (!hasPotentialMatch(content)) {
            return content;
        }
        return applyMaskRules(content);
    }

    /**
     * 粗略判断字符串是否可能命中脱敏规则，用于快速短路。
     *
     * @param content 待检测文本
     * @return true 表示可能包含需要脱敏的内容
     */
    public boolean hasPotentialMatch(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        int consecutiveDigits = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '@') {
                return true;
            }
            if (c >= '0' && c <= '9') {
                consecutiveDigits++;
                if (consecutiveDigits >= 11) {
                    return true;
                }
                continue;
            }
            consecutiveDigits = 0;
        }
        return false;
    }

    public long getMaskTimeoutCount() {
        return maskTimeoutCounter.get();
    }

    private String applyMaskRules(String input) {
        if (input.length() <= 2048) {
            return applyMaskRulesDirect(input);
        }

        TimeBoundedCharSequence bounded = new TimeBoundedCharSequence(input, maskTimeoutMs);
        try {
            String result = input;
            CharSequence current = bounded;
            for (MaskRule rule : rules) {
                result = rule.apply(current);
                current = bounded.wrap(result);
            }
            return result;
        } catch (RegexTimeoutException e) {
            maskTimeoutCounter.incrementAndGet();
            LogCollectInternalLogger.warn(
                    "Mask regex timeout after {}ms on input length={}, returning original content. Detail: {}",
                    maskTimeoutMs, input.length(), e.getMessage()
            );
            return input;
        } catch (RuntimeException e) {
            LogCollectInternalLogger.warn("Mask execution failed", e);
            return input;
        } catch (Error e) {
            throw e;
        }
    }

    private String applyMaskRulesDirect(String input) {
        String result = input;
        try {
            for (MaskRule rule : rules) {
                result = rule.apply(result);
            }
            return result;
        } catch (RuntimeException e) {
            LogCollectInternalLogger.warn("Mask execution failed", e);
            return input;
        } catch (Error e) {
            throw e;
        }
    }

    private long readMaskTimeoutMs() {
        String value = System.getProperty("logcollect.mask.timeout.ms");
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_MASK_TIMEOUT_MS;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : DEFAULT_MASK_TIMEOUT_MS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_MASK_TIMEOUT_MS;
        }
    }
}
