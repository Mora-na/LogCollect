package com.logcollect.api.masker;

/**
 * 敏感信息脱敏器接口。
 *
 * <p>用于将日志中的敏感数据（手机号、证件号、银行卡号、邮箱等）替换为安全展示形式，
 * 防止业务日志泄露个人隐私或凭证信息。
 *
 * <p>执行时机位于净化之后，输入内容默认为“已通过基础安全清洗”的文本。
 */
public interface LogMasker {
    /**
     * 对日志内容执行脱敏。
     *
     * @param content 已净化日志内容
     * @return 脱敏后的日志内容；若未命中规则可返回原值
     */
    String mask(String content);
}
