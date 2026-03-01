package com.logcollect.api.sanitizer;

/**
 * 日志内容净化器接口。
 */
public interface LogSanitizer {

    /**
     * 净化日志消息内容。
     *
     * <p>严格模式：替换换行符、清理控制字符、去除 HTML 标签等。
     * 防止日志注入攻击。
     *
     * @param raw 原始消息内容
     * @return 净化后的安全内容
     */
    String sanitize(String raw);

    /**
     * 净化异常堆栈字符串。
     *
     * <p>宽松模式：保留换行符（\r\n）和制表符（\t），
     * 仅清理 HTML 标签、ANSI 转义码、NUL 等危险控制字符。
     *
     * <p>异常堆栈由 JVM 生成，日志注入风险极低，因此使用宽松策略以保持可读性。
     *
     * @param throwableString 异常堆栈字符串
     * @return 净化后的堆栈字符串
     */
    String sanitizeThrowable(String throwableString);
}
