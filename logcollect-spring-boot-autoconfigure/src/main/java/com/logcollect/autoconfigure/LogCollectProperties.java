package com.logcollect.autoconfigure;

import com.logcollect.api.enums.LogFramework;
import com.logcollect.core.util.DataSizeParser;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "logcollect")
public class LogCollectProperties {

    @NestedConfigurationProperty
    private Global global = new Global();

    @NestedConfigurationProperty
    private Internal internal = new Internal();

    @NestedConfigurationProperty
    private Config config = new Config();

    @NestedConfigurationProperty
    private Logging logging = new Logging();

    private Map<String, MethodConfig> methods = new LinkedHashMap<String, MethodConfig>();

    // ===== 兼容旧接口 =====
    public boolean isEnabled() {
        return global.isEnabled();
    }

    public long getGlobalBufferTotalMaxBytes() {
        return global.getBuffer().getTotalMaxBytesValue();
    }

    public String getInternalLogLevel() {
        return internal.getLogLevel();
    }

    public int getGlobalHandlerTimeoutMs() {
        return global.getHandlerTimeoutMs();
    }

    public int getGlobalMaxNestingDepth() {
        return global.getMaxNestingDepth();
    }

    public int getGlobalDegradeWindowSeconds() {
        return 10;
    }

    // ===== 新结构访问器 =====
    public Global getGlobal() {
        return global;
    }

    public void setGlobal(Global global) {
        this.global = global;
    }

    public Internal getInternal() {
        return internal;
    }

    public void setInternal(Internal internal) {
        this.internal = internal;
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public Logging getLogging() {
        return logging;
    }

    public void setLogging(Logging logging) {
        this.logging = logging;
    }

    public Map<String, MethodConfig> getMethods() {
        return methods;
    }

    public void setMethods(Map<String, MethodConfig> methods) {
        this.methods = methods;
    }

    public static class Global {
        private boolean enabled = true;
        private boolean async = true;
        private String level = "INFO";
        private String collectMode = "AGGREGATE";
        private LogFramework logFramework = LogFramework.AUTO;

        @NestedConfigurationProperty
        private Buffer buffer = new Buffer();

        @NestedConfigurationProperty
        private Degrade degrade = new Degrade();

        @NestedConfigurationProperty
        private Security security = new Security();

        private int handlerTimeoutMs = 5000;
        private int maxNestingDepth = 10;

        @NestedConfigurationProperty
        private Metrics metrics = new Metrics();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getCollectMode() {
            return collectMode;
        }

        public void setCollectMode(String collectMode) {
            this.collectMode = collectMode;
        }

        public LogFramework getLogFramework() {
            return logFramework;
        }

        public void setLogFramework(LogFramework logFramework) {
            this.logFramework = logFramework;
        }

        public Buffer getBuffer() {
            return buffer;
        }

        public void setBuffer(Buffer buffer) {
            this.buffer = buffer;
        }

        public Degrade getDegrade() {
            return degrade;
        }

        public void setDegrade(Degrade degrade) {
            this.degrade = degrade;
        }

        public Security getSecurity() {
            return security;
        }

        public void setSecurity(Security security) {
            this.security = security;
        }

        public int getHandlerTimeoutMs() {
            return handlerTimeoutMs;
        }

        public void setHandlerTimeoutMs(int handlerTimeoutMs) {
            this.handlerTimeoutMs = handlerTimeoutMs;
        }

        public int getMaxNestingDepth() {
            return maxNestingDepth;
        }

        public void setMaxNestingDepth(int maxNestingDepth) {
            this.maxNestingDepth = maxNestingDepth;
        }

        public Metrics getMetrics() {
            return metrics;
        }

        public void setMetrics(Metrics metrics) {
            this.metrics = metrics;
        }
    }

    public static class Buffer {
        private boolean enabled = true;
        private int maxSize = 100;
        private String maxBytes = "1MB";
        private String totalMaxBytes = "100MB";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public String getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(String maxBytes) {
            this.maxBytes = maxBytes;
        }

        public String getTotalMaxBytes() {
            return totalMaxBytes;
        }

        public void setTotalMaxBytes(String totalMaxBytes) {
            this.totalMaxBytes = totalMaxBytes;
        }

        public long getMaxBytesValue() {
            return DataSizeParser.parseToBytes(maxBytes);
        }

        public long getTotalMaxBytesValue() {
            return DataSizeParser.parseToBytes(totalMaxBytes);
        }
    }

    public static class Degrade {
        private boolean enabled = true;
        private int failThreshold = 5;
        private String storage = "FILE";
        private int recoverIntervalSeconds = 30;
        private int recoverMaxIntervalSeconds = 300;
        private int halfOpenPassCount = 3;
        private int halfOpenSuccessThreshold = 3;
        private boolean blockWhenDegradeFail = false;

        @NestedConfigurationProperty
        private DegradeFile file = new DegradeFile();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailThreshold() {
            return failThreshold;
        }

        public void setFailThreshold(int failThreshold) {
            this.failThreshold = failThreshold;
        }

        public String getStorage() {
            return storage;
        }

        public void setStorage(String storage) {
            this.storage = storage;
        }

        public int getRecoverIntervalSeconds() {
            return recoverIntervalSeconds;
        }

        public void setRecoverIntervalSeconds(int recoverIntervalSeconds) {
            this.recoverIntervalSeconds = recoverIntervalSeconds;
        }

        public int getRecoverMaxIntervalSeconds() {
            return recoverMaxIntervalSeconds;
        }

        public void setRecoverMaxIntervalSeconds(int recoverMaxIntervalSeconds) {
            this.recoverMaxIntervalSeconds = recoverMaxIntervalSeconds;
        }

        public int getHalfOpenPassCount() {
            return halfOpenPassCount;
        }

        public void setHalfOpenPassCount(int halfOpenPassCount) {
            this.halfOpenPassCount = halfOpenPassCount;
        }

        public int getHalfOpenSuccessThreshold() {
            return halfOpenSuccessThreshold;
        }

        public void setHalfOpenSuccessThreshold(int halfOpenSuccessThreshold) {
            this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
        }

        public boolean isBlockWhenDegradeFail() {
            return blockWhenDegradeFail;
        }

        public void setBlockWhenDegradeFail(boolean blockWhenDegradeFail) {
            this.blockWhenDegradeFail = blockWhenDegradeFail;
        }

        public DegradeFile getFile() {
            return file;
        }

        public void setFile(DegradeFile file) {
            this.file = file;
        }
    }

    public static class DegradeFile {
        private String maxTotalSize = "500MB";
        private int ttlDays = 90;
        private boolean encryptEnabled = false;
        private String baseDir;
        private String encryptKey;

        public String getMaxTotalSize() {
            return maxTotalSize;
        }

        public void setMaxTotalSize(String maxTotalSize) {
            this.maxTotalSize = maxTotalSize;
        }

        public int getTtlDays() {
            return ttlDays;
        }

        public void setTtlDays(int ttlDays) {
            this.ttlDays = ttlDays;
        }

        public boolean isEncryptEnabled() {
            return encryptEnabled;
        }

        public void setEncryptEnabled(boolean encryptEnabled) {
            this.encryptEnabled = encryptEnabled;
        }

        public String getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(String baseDir) {
            this.baseDir = baseDir;
        }

        public String getEncryptKey() {
            return encryptKey;
        }

        public void setEncryptKey(String encryptKey) {
            this.encryptKey = encryptKey;
        }

        public long getMaxTotalSizeValue() {
            return DataSizeParser.parseToBytes(maxTotalSize);
        }
    }

    public static class Security {
        @NestedConfigurationProperty
        private Sanitize sanitize = new Sanitize();

        @NestedConfigurationProperty
        private Mask mask = new Mask();

        public Sanitize getSanitize() {
            return sanitize;
        }

        public void setSanitize(Sanitize sanitize) {
            this.sanitize = sanitize;
        }

        public Mask getMask() {
            return mask;
        }

        public void setMask(Mask mask) {
            this.mask = mask;
        }
    }

    public static class Sanitize {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mask {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Metrics {
        private boolean enabled = true;
        private String prefix = "logcollect";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }

    public static class Internal {
        private String logLevel = "INFO";

        public String getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }
    }

    public static class Config {
        @NestedConfigurationProperty
        private LocalCache localCache = new LocalCache();

        public LocalCache getLocalCache() {
            return localCache;
        }

        public void setLocalCache(LocalCache localCache) {
            this.localCache = localCache;
        }
    }

    public static class LocalCache {
        private boolean enabled = true;
        private String path = System.getProperty("user.home") + "/.logcollect/config-cache.properties";
        private int maxAgeDays = 7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getMaxAgeDays() {
            return maxAgeDays;
        }

        public void setMaxAgeDays(int maxAgeDays) {
            this.maxAgeDays = maxAgeDays;
        }
    }

    public static class Logging {
        private boolean autoRegisterAppender = true;
        private String appenderName = "LOG_COLLECT";

        public boolean isAutoRegisterAppender() {
            return autoRegisterAppender;
        }

        public void setAutoRegisterAppender(boolean autoRegisterAppender) {
            this.autoRegisterAppender = autoRegisterAppender;
        }

        public String getAppenderName() {
            return appenderName;
        }

        public void setAppenderName(String appenderName) {
            this.appenderName = appenderName;
        }
    }

    public static class MethodConfig {
        private String level;
        private Boolean async;
        private String collectMode;

        @NestedConfigurationProperty
        private BufferConfig buffer = new BufferConfig();

        @NestedConfigurationProperty
        private DegradeConfig degrade = new DegradeConfig();

        private Boolean enableSanitize;
        private Boolean enableMask;
        private Integer handlerTimeoutMs;
        private Boolean transactionIsolation;
        private Integer maxNestingDepth;
        private Boolean enableMetrics;

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public Boolean getAsync() {
            return async;
        }

        public void setAsync(Boolean async) {
            this.async = async;
        }

        public String getCollectMode() {
            return collectMode;
        }

        public void setCollectMode(String collectMode) {
            this.collectMode = collectMode;
        }

        public BufferConfig getBuffer() {
            return buffer;
        }

        public void setBuffer(BufferConfig buffer) {
            this.buffer = buffer;
        }

        public DegradeConfig getDegrade() {
            return degrade;
        }

        public void setDegrade(DegradeConfig degrade) {
            this.degrade = degrade;
        }

        public Boolean getEnableSanitize() {
            return enableSanitize;
        }

        public void setEnableSanitize(Boolean enableSanitize) {
            this.enableSanitize = enableSanitize;
        }

        public Boolean getEnableMask() {
            return enableMask;
        }

        public void setEnableMask(Boolean enableMask) {
            this.enableMask = enableMask;
        }

        public Integer getHandlerTimeoutMs() {
            return handlerTimeoutMs;
        }

        public void setHandlerTimeoutMs(Integer handlerTimeoutMs) {
            this.handlerTimeoutMs = handlerTimeoutMs;
        }

        public Boolean getTransactionIsolation() {
            return transactionIsolation;
        }

        public void setTransactionIsolation(Boolean transactionIsolation) {
            this.transactionIsolation = transactionIsolation;
        }

        public Integer getMaxNestingDepth() {
            return maxNestingDepth;
        }

        public void setMaxNestingDepth(Integer maxNestingDepth) {
            this.maxNestingDepth = maxNestingDepth;
        }

        public Boolean getEnableMetrics() {
            return enableMetrics;
        }

        public void setEnableMetrics(Boolean enableMetrics) {
            this.enableMetrics = enableMetrics;
        }
    }

    public static class BufferConfig {
        private Boolean enabled;
        private Integer maxSize;
        private String maxBytes;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(Integer maxSize) {
            this.maxSize = maxSize;
        }

        public String getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(String maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class DegradeConfig {
        private Boolean enabled;
        private Integer failThreshold;
        private String storage;
        private Integer recoverIntervalSeconds;
        private Integer recoverMaxIntervalSeconds;
        private Integer halfOpenPassCount;
        private Integer halfOpenSuccessThreshold;
        private Boolean blockWhenDegradeFail;

        @NestedConfigurationProperty
        private DegradeFileConfig file = new DegradeFileConfig();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getFailThreshold() {
            return failThreshold;
        }

        public void setFailThreshold(Integer failThreshold) {
            this.failThreshold = failThreshold;
        }

        public String getStorage() {
            return storage;
        }

        public void setStorage(String storage) {
            this.storage = storage;
        }

        public Integer getRecoverIntervalSeconds() {
            return recoverIntervalSeconds;
        }

        public void setRecoverIntervalSeconds(Integer recoverIntervalSeconds) {
            this.recoverIntervalSeconds = recoverIntervalSeconds;
        }

        public Integer getRecoverMaxIntervalSeconds() {
            return recoverMaxIntervalSeconds;
        }

        public void setRecoverMaxIntervalSeconds(Integer recoverMaxIntervalSeconds) {
            this.recoverMaxIntervalSeconds = recoverMaxIntervalSeconds;
        }

        public Integer getHalfOpenPassCount() {
            return halfOpenPassCount;
        }

        public void setHalfOpenPassCount(Integer halfOpenPassCount) {
            this.halfOpenPassCount = halfOpenPassCount;
        }

        public Integer getHalfOpenSuccessThreshold() {
            return halfOpenSuccessThreshold;
        }

        public void setHalfOpenSuccessThreshold(Integer halfOpenSuccessThreshold) {
            this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
        }

        public Boolean getBlockWhenDegradeFail() {
            return blockWhenDegradeFail;
        }

        public void setBlockWhenDegradeFail(Boolean blockWhenDegradeFail) {
            this.blockWhenDegradeFail = blockWhenDegradeFail;
        }

        public DegradeFileConfig getFile() {
            return file;
        }

        public void setFile(DegradeFileConfig file) {
            this.file = file;
        }
    }

    public static class DegradeFileConfig {
        private String maxTotalSize;
        private Integer ttlDays;
        private Boolean encryptEnabled;

        public String getMaxTotalSize() {
            return maxTotalSize;
        }

        public void setMaxTotalSize(String maxTotalSize) {
            this.maxTotalSize = maxTotalSize;
        }

        public Integer getTtlDays() {
            return ttlDays;
        }

        public void setTtlDays(Integer ttlDays) {
            this.ttlDays = ttlDays;
        }

        public Boolean getEncryptEnabled() {
            return encryptEnabled;
        }

        public void setEncryptEnabled(Boolean encryptEnabled) {
            this.encryptEnabled = encryptEnabled;
        }
    }
}
