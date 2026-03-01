package com.logcollect.core.util;

import java.lang.reflect.Method;

/**
 * 方法标识统一转换工具。
 *
 * <p>显示格式（端点/日志/指标）：com.example.OrderService#placeOrder
 * <p>配置格式（properties key）：com_example_OrderService_placeOrder
 */
public final class MethodKeyResolver {

    private MethodKeyResolver() {
    }

    public static String toDisplayKey(Method method) {
        if (method == null || method.getDeclaringClass() == null) {
            return "unknown#unknown";
        }
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    public static String toDisplayKey(String className, String methodName) {
        String safeClass = className == null ? "unknown" : className;
        String safeMethod = methodName == null ? "unknown" : methodName;
        return safeClass + "#" + safeMethod;
    }

    public static String toConfigKey(Method method) {
        if (method == null || method.getDeclaringClass() == null) {
            return "unknown_unknown";
        }
        return method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName();
    }

    public static String configKeyToDisplayKey(String configKey) {
        if (configKey == null || configKey.trim().isEmpty()) {
            return configKey;
        }
        int lastUnderscore = configKey.lastIndexOf('_');
        if (lastUnderscore < 0) {
            return configKey;
        }
        String classPart = configKey.substring(0, lastUnderscore).replace('_', '.');
        String methodPart = configKey.substring(lastUnderscore + 1);
        if (classPart.trim().isEmpty() || methodPart.trim().isEmpty()) {
            return configKey;
        }
        return classPart + "#" + methodPart;
    }

    public static String displayKeyToConfigKey(String displayKey) {
        if (displayKey == null) {
            return null;
        }
        return displayKey.replace('.', '_').replace('#', '_');
    }

    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.contains("#")) {
            return trimmed;
        }
        return configKeyToDisplayKey(trimmed);
    }
}
