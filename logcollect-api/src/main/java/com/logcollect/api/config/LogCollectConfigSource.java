package com.logcollect.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface LogCollectConfigSource {

    /**
     * 全局配置，返回相对 key（如 buffer.max-size、degrade.fail-threshold）。
     */
    default Map<String, String> getGlobalProperties() {
        return Collections.emptyMap();
    }

    /**
     * 方法级配置，返回相对 key（如 level、async、buffer.max-size）。
     */
    default Map<String, String> getMethodProperties(String methodKey) {
        return Collections.emptyMap();
    }

    /**
     * 兼容旧接口。
     */
    @Deprecated
    default Map<String, String> getProperties(String prefix) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        Map<String, String> global = getGlobalProperties();
        if (global != null) {
            for (Map.Entry<String, String> entry : global.entrySet()) {
                merged.put(prefix + "global." + entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * 注册配置变更监听器。
     */
    default void addChangeListener(Runnable listener) {
        // no-op
    }

    /**
     * 兼容旧监听签名。
     */
    @Deprecated
    default void addChangeListener(LogCollectConfigChangeListener listener) {
        if (listener == null) {
            return;
        }
        addChangeListener(() -> listener.onChange(Collections.<String, String>emptyMap()));
    }

    default String getType() {
        return getClass().getSimpleName();
    }

    default boolean isAvailable() {
        return true;
    }

    default int getOrder() {
        return 100;
    }

    default void refresh() {
        // no-op
    }
}
