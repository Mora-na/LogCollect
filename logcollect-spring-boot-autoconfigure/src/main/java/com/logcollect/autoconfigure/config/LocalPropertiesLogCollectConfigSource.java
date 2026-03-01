package com.logcollect.autoconfigure.config;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.core.util.MethodKeyResolver;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Environment 配置源。
 *
 * <p>仅返回用户显式配置的 key，跳过 logcollect-default.properties 默认值来源。
 */
public class LocalPropertiesLogCollectConfigSource implements LogCollectConfigSource {

    private static final String GLOBAL_PREFIX = "logcollect.global.";
    private static final String METHODS_PREFIX = "logcollect.methods.";
    private static final String DEFAULT_SOURCE_MARK = "logcollect-default";

    private final Environment environment;

    public LocalPropertiesLogCollectConfigSource(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Map<String, String> getGlobalProperties() {
        Map<String, String> explicit = explicitLogCollectProperties();
        if (explicit.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : explicit.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(GLOBAL_PREFIX)) {
                continue;
            }
            result.put(key.substring(GLOBAL_PREFIX.length()), entry.getValue());
        }
        return result;
    }

    @Override
    public Map<String, String> getMethodProperties(String methodKey) {
        Map<String, String> explicit = explicitLogCollectProperties();
        if (explicit.isEmpty() || methodKey == null || methodKey.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        String normalizedMethodKey = MethodKeyResolver.displayKeyToConfigKey(MethodKeyResolver.normalize(methodKey));
        String prefix = METHODS_PREFIX + normalizedMethodKey + ".";
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : explicit.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            String relative = key.substring(prefix.length());
            // FILE 存储参数是全局物理约束，不支持方法级覆盖。
            if (relative.startsWith("degrade.file.")) {
                continue;
            }
            result.put(relative, entry.getValue());
        }
        return result;
    }

    @Override
    public Map<String, String> getAllProperties() {
        return explicitLogCollectProperties();
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public int getOrder() {
        return 1000;
    }

    private Map<String, String> explicitLogCollectProperties() {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return Collections.emptyMap();
        }
        ConfigurableEnvironment configurableEnvironment = (ConfigurableEnvironment) environment;
        Map<String, String> result = new LinkedHashMap<String, String>();

        for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
            if (source == null || isDefaultLogCollectSource(source)) {
                continue;
            }
            if (!(source instanceof EnumerablePropertySource)) {
                continue;
            }
            String[] propertyNames = ((EnumerablePropertySource<?>) source).getPropertyNames();
            if (propertyNames == null || propertyNames.length == 0) {
                continue;
            }
            for (String propertyName : propertyNames) {
                if (propertyName == null || !propertyName.startsWith("logcollect.")) {
                    continue;
                }
                // 保留最高优先级来源（PropertySources 顺序通常已按优先级排列）。
                if (result.containsKey(propertyName)) {
                    continue;
                }
                Object value = source.getProperty(propertyName);
                if (value != null) {
                    result.put(propertyName, value.toString());
                }
            }
        }
        return result;
    }

    private boolean isDefaultLogCollectSource(PropertySource<?> source) {
        String name = source.getName();
        return name != null && name.contains(DEFAULT_SOURCE_MARK);
    }
}
