package com.logcollect.api.format;

/**
 * 日志行格式默认值持有者。
 *
 * <p>框架启动时由适配器层的 ConsolePatternDetector 检测当前项目控制台 Appender 的实际
 * pattern 并清理后设置。若未设置（检测失败或非 Spring 环境），使用内置硬编码兜底。
 */
public final class LogLineDefaults {

    /** 硬编码兜底 pattern：接近 Spring Boot 默认控制台格式（去除颜色/PID） */
    private static final String FALLBACK_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%15.15t] %-40.40c{39} - %m%ex%n";

    /** 检测到的 pattern。 */
    private static volatile String detectedPattern;

    /** 配置中心动态覆盖的 pattern。 */
    private static volatile String configuredPattern;

    private LogLineDefaults() {
    }

    /**
     * 由适配层在框架初始化阶段调用，设置检测到的 pattern。
     * 仅接受第一次非 null 设置，后续调用忽略（避免多适配器冲突）。
     */
    public static void setDetectedPattern(String pattern) {
        if (pattern != null && detectedPattern == null) {
            detectedPattern = pattern;
        }
    }

    /**
     * 供配置中心动态覆盖全局 pattern。
     */
    public static void setConfiguredPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            configuredPattern = null;
            return;
        }
        configuredPattern = pattern.trim();
    }

    /**
     * 获取当前生效 pattern：配置中心覆盖 > 检测值 > 兜底值。
     */
    public static String getDetectedPattern() {
        String configured = configuredPattern;
        if (configured != null) {
            return configured;
        }
        String detected = detectedPattern;
        return detected != null ? detected : FALLBACK_PATTERN;
    }

    public static String getFallbackPattern() {
        return FALLBACK_PATTERN;
    }
}
