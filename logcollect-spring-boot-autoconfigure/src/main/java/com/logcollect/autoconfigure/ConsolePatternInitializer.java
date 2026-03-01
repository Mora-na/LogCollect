package com.logcollect.autoconfigure;

import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.core.format.ConsolePatternDetector;
import com.logcollect.core.format.PatternCleaner;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.List;

/**
 * 在框架自动装配阶段，检测控制台 pattern 并注入 LogLineDefaults。
 */
final class ConsolePatternInitializer {

    private ConsolePatternInitializer() {
    }

    /**
     * 使用所有可用的 ConsolePatternDetector 尝试检测。
     * 第一个成功的结果经清理后设置为全局默认 pattern。
     */
    static void initialize(List<ConsolePatternDetector> detectors) {
        if (detectors == null || detectors.isEmpty()) {
            LogCollectInternalLogger.info(
                    "No console pattern detector found, using fallback: [{}]",
                    LogLineDefaults.getFallbackPattern());
            return;
        }

        for (ConsolePatternDetector detector : detectors) {
            if (detector == null || !detector.isAvailable()) {
                continue;
            }
            try {
                String rawPattern = detector.detectRawPattern();
                if (rawPattern == null || rawPattern.isEmpty()) {
                    continue;
                }

                String cleanedPattern = PatternCleaner.clean(rawPattern);
                if (cleanedPattern == null || cleanedPattern.isEmpty()) {
                    continue;
                }

                // 至少要能输出消息占位符
                if (!containsMessagePlaceholder(cleanedPattern)) {
                    LogCollectInternalLogger.warn(
                            "Detected pattern missing message placeholder, ignoring: {}",
                            cleanedPattern);
                    continue;
                }

                LogLineDefaults.setDetectedPattern(cleanedPattern);
                LogCollectInternalLogger.info(
                        "Console log pattern detected and set: [{}] (raw: [{}])",
                        cleanedPattern,
                        rawPattern);
                return;
            } catch (Exception e) {
                LogCollectInternalLogger.debug(
                        "ConsolePatternDetector [{}] failed: {}",
                        detector.getClass().getSimpleName(),
                        e.getMessage());
            }
        }

        LogCollectInternalLogger.info(
                "No console pattern detected, using fallback: [{}]",
                LogLineDefaults.getFallbackPattern());
    }

    private static boolean containsMessagePlaceholder(String pattern) {
        return pattern.contains("%m") || pattern.contains("%msg");
    }
}
