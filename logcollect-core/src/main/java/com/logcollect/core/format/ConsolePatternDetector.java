package com.logcollect.core.format;

/**
 * 控制台 Appender Pattern 探测器 SPI。
 *
 * <p>由 Logback/Log4j2 适配层分别实现，框架启动时自动发现并调用。
 */
public interface ConsolePatternDetector {

    /**
     * 尝试从当前日志框架的控制台 Appender 中提取 pattern。
     *
     * @return 原始 pattern 字符串（含颜色等），检测失败返回 null
     */
    String detectRawPattern();

    /**
     * 当前检测器是否可用（对应的日志框架是否在 classpath 中）。
     *
     * @return true 表示当前运行环境可用该检测器
     */
    boolean isAvailable();

    /**
     * 检测器优先级，值越小优先级越高。
     *
     * @return 排序优先级
     */
    default int getOrder() {
        return 0;
    }
}
