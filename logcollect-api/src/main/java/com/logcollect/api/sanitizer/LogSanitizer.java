package com.logcollect.api.sanitizer;

/**
 * 日志净化器接口。
 *
 * <p>用于在日志进入缓冲区前执行“安全清洗”，典型场景包括：
 * 去除换行注入、剔除控制字符、移除危险脚本片段等。
 *
 * <p>执行顺序：级别过滤 -> {@code shouldCollect} -> {@code sanitize} -> {@code mask}。
 * 建议实现为无状态或线程安全组件。
 */
public interface LogSanitizer {
    /**
     * 对原始日志内容进行净化处理。
     *
     * @param rawContent 原始日志文本（可能包含用户输入）
     * @return 净化后的日志文本；若无需处理可返回原值
     */
    String sanitize(String rawContent);
}
