package com.logcollect.autoconfigure.config;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.autoconfigure.LogCollectProperties;

import java.util.LinkedHashMap;
import java.util.Map;

public class LocalPropertiesLogCollectConfigSource implements LogCollectConfigSource {

    private final LogCollectProperties properties;

    public LocalPropertiesLogCollectConfigSource(LogCollectProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, String> getGlobalProperties() {
        LogCollectProperties.Global global = properties.getGlobal();
        Map<String, String> result = new LinkedHashMap<String, String>();

        result.put("enabled", Boolean.toString(global.isEnabled()));
        result.put("async", Boolean.toString(global.isAsync()));
        result.put("level", global.getLevel());
        result.put("collect-mode", global.getCollectMode());
        result.put("log-framework", global.getLogFramework().name());

        LogCollectProperties.Buffer buffer = global.getBuffer();
        result.put("buffer.enabled", Boolean.toString(buffer.isEnabled()));
        result.put("buffer.max-size", Integer.toString(buffer.getMaxSize()));
        result.put("buffer.max-bytes", buffer.getMaxBytes());
        result.put("buffer.total-max-bytes", buffer.getTotalMaxBytes());

        LogCollectProperties.Degrade degrade = global.getDegrade();
        result.put("degrade.enabled", Boolean.toString(degrade.isEnabled()));
        result.put("degrade.fail-threshold", Integer.toString(degrade.getFailThreshold()));
        result.put("degrade.storage", degrade.getStorage());
        result.put("degrade.recover-interval-seconds", Integer.toString(degrade.getRecoverIntervalSeconds()));
        result.put("degrade.recover-max-interval-seconds", Integer.toString(degrade.getRecoverMaxIntervalSeconds()));
        result.put("degrade.half-open-pass-count", Integer.toString(degrade.getHalfOpenPassCount()));
        result.put("degrade.half-open-success-threshold", Integer.toString(degrade.getHalfOpenSuccessThreshold()));
        result.put("degrade.block-when-degrade-fail", Boolean.toString(degrade.isBlockWhenDegradeFail()));

        LogCollectProperties.DegradeFile file = degrade.getFile();
        result.put("degrade.file.max-total-size", file.getMaxTotalSize());
        result.put("degrade.file.ttl-days", Integer.toString(file.getTtlDays()));
        result.put("degrade.file.encrypt-enabled", Boolean.toString(file.isEncryptEnabled()));

        result.put("security.sanitize.enabled", Boolean.toString(global.getSecurity().getSanitize().isEnabled()));
        result.put("security.mask.enabled", Boolean.toString(global.getSecurity().getMask().isEnabled()));

        result.put("handler-timeout-ms", Integer.toString(global.getHandlerTimeoutMs()));
        result.put("max-nesting-depth", Integer.toString(global.getMaxNestingDepth()));

        result.put("metrics.enabled", Boolean.toString(global.getMetrics().isEnabled()));
        result.put("metrics.prefix", global.getMetrics().getPrefix());

        return result;
    }

    @Override
    public Map<String, String> getMethodProperties(String methodKey) {
        Map<String, LogCollectProperties.MethodConfig> all = properties.getMethods();
        if (all == null || all.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        LogCollectProperties.MethodConfig method = all.get(methodKey);
        if (method == null) {
            return java.util.Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<String, String>();
        if (method.getLevel() != null) {
            result.put("level", method.getLevel());
        }
        if (method.getAsync() != null) {
            result.put("async", method.getAsync().toString());
        }
        if (method.getCollectMode() != null) {
            result.put("collect-mode", method.getCollectMode());
        }
        if (method.getEnableSanitize() != null) {
            result.put("security.sanitize.enabled", method.getEnableSanitize().toString());
        }
        if (method.getEnableMask() != null) {
            result.put("security.mask.enabled", method.getEnableMask().toString());
        }
        if (method.getHandlerTimeoutMs() != null) {
            result.put("handler-timeout-ms", method.getHandlerTimeoutMs().toString());
        }
        if (method.getTransactionIsolation() != null) {
            result.put("transaction-isolation", method.getTransactionIsolation().toString());
        }
        if (method.getMaxNestingDepth() != null) {
            result.put("max-nesting-depth", method.getMaxNestingDepth().toString());
        }
        if (method.getEnableMetrics() != null) {
            result.put("metrics.enabled", method.getEnableMetrics().toString());
        }

        LogCollectProperties.BufferConfig buffer = method.getBuffer();
        if (buffer != null) {
            if (buffer.getEnabled() != null) {
                result.put("buffer.enabled", buffer.getEnabled().toString());
            }
            if (buffer.getMaxSize() != null) {
                result.put("buffer.max-size", buffer.getMaxSize().toString());
            }
            if (buffer.getMaxBytes() != null) {
                result.put("buffer.max-bytes", buffer.getMaxBytes());
            }
        }

        LogCollectProperties.DegradeConfig degrade = method.getDegrade();
        if (degrade != null) {
            if (degrade.getEnabled() != null) {
                result.put("degrade.enabled", degrade.getEnabled().toString());
            }
            if (degrade.getFailThreshold() != null) {
                result.put("degrade.fail-threshold", degrade.getFailThreshold().toString());
            }
            if (degrade.getStorage() != null) {
                result.put("degrade.storage", degrade.getStorage());
            }
            if (degrade.getRecoverIntervalSeconds() != null) {
                result.put("degrade.recover-interval-seconds", degrade.getRecoverIntervalSeconds().toString());
            }
            if (degrade.getRecoverMaxIntervalSeconds() != null) {
                result.put("degrade.recover-max-interval-seconds", degrade.getRecoverMaxIntervalSeconds().toString());
            }
            if (degrade.getHalfOpenPassCount() != null) {
                result.put("degrade.half-open-pass-count", degrade.getHalfOpenPassCount().toString());
            }
            if (degrade.getHalfOpenSuccessThreshold() != null) {
                result.put("degrade.half-open-success-threshold", degrade.getHalfOpenSuccessThreshold().toString());
            }
            if (degrade.getBlockWhenDegradeFail() != null) {
                result.put("degrade.block-when-degrade-fail", degrade.getBlockWhenDegradeFail().toString());
            }
            LogCollectProperties.DegradeFileConfig file = degrade.getFile();
            if (file != null) {
                if (file.getMaxTotalSize() != null) {
                    result.put("degrade.file.max-total-size", file.getMaxTotalSize());
                }
                if (file.getTtlDays() != null) {
                    result.put("degrade.file.ttl-days", file.getTtlDays().toString());
                }
                if (file.getEncryptEnabled() != null) {
                    result.put("degrade.file.encrypt-enabled", file.getEncryptEnabled().toString());
                }
            }
        }

        return result;
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
