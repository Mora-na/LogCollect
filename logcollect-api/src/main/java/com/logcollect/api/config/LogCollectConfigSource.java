package com.logcollect.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

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
     * 全量配置（含全局 + 方法级），返回完整 key（如 logcollect.global.xxx / logcollect.methods.xxx.xxx）。
     */
    default Map<String, String> getAllProperties() {
        Map<String, String> all = new LinkedHashMap<String, String>();
        Map<String, String> global = getGlobalProperties();
        if (global != null) {
            for (Map.Entry<String, String> entry : global.entrySet()) {
                all.put("logcollect.global." + entry.getKey(), entry.getValue());
            }
        }
        return all;
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
    default void addChangeListener(Consumer<String> listener) {
        // no-op
    }

    /**
     * 兼容旧监听签名。
     */
    @Deprecated
    default void addChangeListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        addChangeListener((Consumer<String>) source -> listener.run());
    }

    /**
     * 兼容旧监听签名。
     */
    @Deprecated
    default void addChangeListener(LogCollectConfigChangeListener listener) {
        if (listener == null) {
            return;
        }
        addChangeListener((Consumer<String>) source -> listener.onChange(Collections.<String, String>emptyMap()));
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
