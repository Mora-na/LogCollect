package com.logcollect.core.security;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认脱敏器实现。
 *
 * <p>内置手机号、身份证号、银行卡号、邮箱等规则，并支持运行时追加自定义规则。
 */
public class DefaultLogMasker implements LogMasker {
    private static final long DEFAULT_MASK_TIMEOUT_MS = 50L;
    private static final ExecutorService MASK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "logcollect-mask-executor");
        t.setDaemon(true);
        return t;
    });

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
        Pattern phone = Pattern.compile("\\b1[3-9]\\d{9}\\b");
        rules.add(new MaskRule(phone, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                return v.substring(0, 3) + "****" + v.substring(7);
            }
        }, "1"));

        Pattern id = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
        rules.add(new MaskRule(id, new Function<Matcher, String>() {
            @Override
            public String apply(Matcher m) {
                String v = m.group();
                return v.substring(0, 6) + "********" + v.substring(14);
            }
        }));

        Pattern bank = Pattern.compile("\\b\\d{12,19}\\b");
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
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '@' || (c >= '0' && c <= '9')) {
                return true;
            }
        }
        return false;
    }

    public long getMaskTimeoutCount() {
        return maskTimeoutCounter.get();
    }

    private String applyMaskRules(String input) {
        Future<String> future = MASK_EXECUTOR.submit(() -> {
            String result = input;
            for (MaskRule rule : rules) {
                result = rule.apply(result);
            }
            return result;
        });
        try {
            return future.get(maskTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            maskTimeoutCounter.incrementAndGet();
            return input;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return input;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            LogCollectInternalLogger.warn("Mask rule execution failed", cause == null ? e : cause);
            return input;
        } catch (Exception e) {
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
