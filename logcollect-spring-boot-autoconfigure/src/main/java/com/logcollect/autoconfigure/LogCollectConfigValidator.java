package com.logcollect.autoconfigure;

import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Map;

/**
 * 启动期配置一致性校验。
 */
public class LogCollectConfigValidator implements SmartInitializingSingleton {

    private final LogCollectConfigResolver configResolver;
    private final GlobalBufferMemoryManager globalMemoryManager;
    private final LogCollectProperties properties;

    public LogCollectConfigValidator(LogCollectConfigResolver configResolver,
                                     GlobalBufferMemoryManager globalMemoryManager,
                                     LogCollectProperties properties) {
        this.configResolver = configResolver;
        this.globalMemoryManager = globalMemoryManager;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateGlobalProperties();
        validateCachedMethodConfigs();
        validateGlobalLimits();
        validatePipelineConfig();
    }

    private void validateGlobalProperties() {
        if (configResolver == null) {
            return;
        }
        Map<String, String> globals = configResolver.getLatestGlobalProperties();
        if (globals == null || globals.isEmpty()) {
            return;
        }
        String overflow = globals.get("buffer.overflow-strategy");
        if ("DROP_NEWEST".equalsIgnoreCase(overflow) || "DROP_OLDEST".equalsIgnoreCase(overflow)) {
            LogCollectInternalLogger.info(
                    "Global overflow strategy {} is enabled. Overflow entries will be routed to degradation (not discarded).",
                    overflow);
        }
        if (globals.containsKey("guard.max-content-length") || globals.containsKey("guard.max-throwable-length")) {
            LogCollectInternalLogger.info(
                    "Deprecated guard config detected: max-content-length/max-throwable-length are ignored since v1.2.0.");
        }
        String degradeStorage = globals.get("degrade.storage");
        if ("DISCARD_ALL".equalsIgnoreCase(degradeStorage)) {
            LogCollectInternalLogger.warn(
                    "Global degrade.storage=DISCARD_ALL. All logs will be lost when handler fails.");
        }
    }

    private void validateCachedMethodConfigs() {
        if (configResolver == null) {
            return;
        }
        for (Map.Entry<String, LogCollectConfig> entry : configResolver.getAllCachedConfigs().entrySet()) {
            String method = entry.getKey();
            LogCollectConfig config = entry.getValue();
            if (config == null) {
                continue;
            }
            String overflow = config.getBufferOverflowStrategy();
            if ("DROP_NEWEST".equalsIgnoreCase(overflow) || "DROP_OLDEST".equalsIgnoreCase(overflow)) {
                LogCollectInternalLogger.info(
                        "Method {} uses {} overflow strategy. Overflow entries will be routed to degradation.",
                        method, overflow);
            }
            if (config.getDegradeStorage() == DegradeStorage.DISCARD_ALL) {
                LogCollectInternalLogger.warn(
                        "Method {} uses DISCARD_ALL degrade storage. All logs will be lost when handler fails.",
                        method);
            }
        }
    }

    private void validateGlobalLimits() {
        if (globalMemoryManager == null) {
            return;
        }
        long soft = globalMemoryManager.getMaxTotalBytes();
        long hard = globalMemoryManager.getHardCeilingBytes();
        if (hard > 0 && soft > 0 && hard < soft) {
            throw new IllegalStateException(
                    "[LogCollect] hard-ceiling-bytes (" + hard + ") < total-max-bytes (" + soft + ")");
        }
    }

    private void validatePipelineConfig() {
        if (properties == null || properties.getGlobal() == null || properties.getGlobal().getPipeline() == null) {
            return;
        }
        int consumerThreads = properties.getGlobal().getPipeline().getConsumerThreads();
        if (consumerThreads < 1 || consumerThreads > 16) {
            LogCollectInternalLogger.warn("consumer-threads={} unusual. Recommended range: 1~4.", consumerThreads);
        }
        int ringCapacity = properties.getGlobal().getPipeline().getRingBufferCapacity();
        if ((ringCapacity & (ringCapacity - 1)) != 0) {
            LogCollectInternalLogger.info(
                    "pipeline.ring-buffer-capacity={} is not a power of 2. RingBuffer will auto round up.",
                    ringCapacity);
        }
    }
}
