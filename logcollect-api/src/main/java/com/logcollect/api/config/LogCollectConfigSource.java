package com.logcollect.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public interface LogCollectConfigSource {

    /**
     * 全局配置，返回相对 key（如 buffer.max-size、degrade.fail-threshold）。
     *
     * @return 全局配置键值对
     */
    default Map<String, String> getGlobalProperties() {
        return Collections.emptyMap();
    }

    /**
     * 方法级配置，返回相对 key（如 level、async、buffer.max-size）。
     *
     * @param methodKey 方法唯一标识
     * @return 方法级配置键值对
     */
    default Map<String, String> getMethodProperties(String methodKey) {
        return Collections.emptyMap();
    }

    /**
     * 全量配置（含全局 + 方法级），返回完整 key（如 logcollect.global.xxx / logcollect.methods.xxx.xxx）。
     *
     * @return 全量配置键值对（完整 key）
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
     * 注册配置变更监听器。
     *
     * @param listener 变更回调监听器
     */
    default void addChangeListener(Consumer<String> listener) {
        // no-op
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
