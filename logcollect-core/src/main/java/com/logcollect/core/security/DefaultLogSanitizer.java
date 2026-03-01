package com.logcollect.core.security;

import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.regex.Pattern;

/**
 * 默认日志净化器实现。
 *
 * <p>对消息内容和异常堆栈采用不同强度的净化策略。
 */
public class DefaultLogSanitizer implements LogSanitizer {

    /** HTML 标签 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** ANSI 转义序列 */
    private static final Pattern ANSI_ESCAPE =
            Pattern.compile("\\x1B\\[[0-9;]*[a-zA-Z]");

    /** 消息净化用：所有控制字符（含 \r\n\t） */
    private static final Pattern MESSAGE_CONTROL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\r\\n\\t]");

    /** 堆栈净化用：仅危险控制字符（保留 \r\n\t） */
    private static final Pattern THROWABLE_DANGEROUS_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    @Override
    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String result = raw;
        result = HTML_TAG.matcher(result).replaceAll("");
        result = ANSI_ESCAPE.matcher(result).replaceAll("");
        result = MESSAGE_CONTROL_CHARS.matcher(result).replaceAll(" ");
        return result;
    }

    @Override
    public String sanitizeThrowable(String throwableString) {
        if (throwableString == null) {
            return null;
        }
        String result = throwableString;
        result = HTML_TAG.matcher(result).replaceAll("");
        result = ANSI_ESCAPE.matcher(result).replaceAll("");
        result = THROWABLE_DANGEROUS_CHARS.matcher(result).replaceAll("");
        return result;
    }
}
