package com.logcollect.autoconfigure;

import com.logcollect.api.enums.LogFramework;
import com.logcollect.core.util.DataSizeParser;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "logcollect")
public class LogCollectProperties {

    private boolean debug = false;

    @NestedConfigurationProperty
    private Global global = new Global();

    @NestedConfigurationProperty
    private Internal internal = new Internal();

    @NestedConfigurationProperty
    private Config config = new Config();

    @NestedConfigurationProperty
    private Logging logging = new Logging();

    private Map<String, MethodConfig> methods = new LinkedHashMap<String, MethodConfig>();

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

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
        private String collectMode = "AUTO";
        private LogFramework logFramework = LogFramework.AUTO;

        @NestedConfigurationProperty
        private Buffer buffer = new Buffer();

        @NestedConfigurationProperty
        private Pipeline pipeline = new Pipeline();

        @NestedConfigurationProperty
        private Flush flush = new Flush();

        @NestedConfigurationProperty
        private Degrade degrade = new Degrade();

        @NestedConfigurationProperty
        private Security security = new Security();

        @NestedConfigurationProperty
        private Guard guard = new Guard();

        private int handlerTimeoutMs = 5000;
        @NestedConfigurationProperty
        private Handler handler = new Handler();
        private int maxNestingDepth = 10;
        private String[] log4j2MdcKeys = new String[0];

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

        public Pipeline getPipeline() {
            return pipeline;
        }

        public void setPipeline(Pipeline pipeline) {
            this.pipeline = pipeline;
        }

        public Flush getFlush() {
            return flush;
        }

        public void setFlush(Flush flush) {
            this.flush = flush;
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

        public Guard getGuard() {
            return guard;
        }

        public void setGuard(Guard guard) {
            this.guard = guard;
        }

        public int getHandlerTimeoutMs() {
            return handlerTimeoutMs;
        }

        public void setHandlerTimeoutMs(int handlerTimeoutMs) {
            this.handlerTimeoutMs = handlerTimeoutMs;
        }

        public Handler getHandler() {
            return handler;
        }

        public void setHandler(Handler handler) {
            this.handler = handler;
        }

        public int getMaxNestingDepth() {
            return maxNestingDepth;
        }

        public void setMaxNestingDepth(int maxNestingDepth) {
            this.maxNestingDepth = maxNestingDepth;
        }

        public String[] getLog4j2MdcKeys() {
            return log4j2MdcKeys;
        }

        public void setLog4j2MdcKeys(String[] log4j2MdcKeys) {
            this.log4j2MdcKeys = log4j2MdcKeys;
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
        private String overflowStrategy = "FLUSH_EARLY";
        private String totalMaxBytes = "100MB";
        private String hardCeilingBytes;
        private String counterMode = "EXACT_CAS";
        private double estimationFactor = 1.0d;
        private long memorySyncThresholdBytes = 4096L;

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

        public String getOverflowStrategy() {
            return overflowStrategy;
        }

        public void setOverflowStrategy(String overflowStrategy) {
            this.overflowStrategy = overflowStrategy;
        }

        public String getCounterMode() {
            return counterMode;
        }

        public void setCounterMode(String counterMode) {
            this.counterMode = counterMode;
        }

        public String getHardCeilingBytes() {
            return hardCeilingBytes;
        }

        public void setHardCeilingBytes(String hardCeilingBytes) {
            this.hardCeilingBytes = hardCeilingBytes;
        }

        public double getEstimationFactor() {
            return estimationFactor;
        }

        public void setEstimationFactor(double estimationFactor) {
            this.estimationFactor = estimationFactor;
        }

        public long getMemorySyncThresholdBytes() {
            return memorySyncThresholdBytes;
        }

        public void setMemorySyncThresholdBytes(long memorySyncThresholdBytes) {
            this.memorySyncThresholdBytes = memorySyncThresholdBytes;
        }

        public long getMaxBytesValue() {
            return DataSizeParser.parseToBytes(maxBytes);
        }

        public long getTotalMaxBytesValue() {
            return DataSizeParser.parseToBytes(totalMaxBytes);
        }

        public long getHardCeilingBytesValue() {
            if (hardCeilingBytes == null || hardCeilingBytes.trim().isEmpty()) {
                return 0L;
            }
            return DataSizeParser.parseToBytes(hardCeilingBytes);
        }
    }

    public static class Pipeline {
        private boolean enabled = true;
        /**
         * @deprecated replaced by ringBufferCapacity in v2.1.
         */
        private int queueCapacity = 4096;
        private int ringBufferCapacity = 4096;
        private int overflowQueueCapacity = 1024;
        private int unpublishedSlotTimeoutMs = 100;
        private String consumerIdleStrategy = "PARK";
        private int consumerDrainBatch = 64;
        private int consumerSpinThreshold = 100;
        private int consumerYieldThreshold = 200;
        private int consumerCursorAdvanceInterval = 8;
        private int consumerThreads = 2;
        private double backpressureWarning = 0.7d;
        private double backpressureCritical = 0.9d;
        private int handoffTimeoutMs = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            this.ringBufferCapacity = queueCapacity;
        }

        public int getRingBufferCapacity() {
            return ringBufferCapacity;
        }

        public void setRingBufferCapacity(int ringBufferCapacity) {
            this.ringBufferCapacity = ringBufferCapacity;
            this.queueCapacity = ringBufferCapacity;
        }

        public int getOverflowQueueCapacity() {
            return overflowQueueCapacity;
        }

        public void setOverflowQueueCapacity(int overflowQueueCapacity) {
            this.overflowQueueCapacity = overflowQueueCapacity;
        }

        public int getUnpublishedSlotTimeoutMs() {
            return unpublishedSlotTimeoutMs;
        }

        public void setUnpublishedSlotTimeoutMs(int unpublishedSlotTimeoutMs) {
            this.unpublishedSlotTimeoutMs = unpublishedSlotTimeoutMs;
        }

        public String getConsumerIdleStrategy() {
            return consumerIdleStrategy;
        }

        public void setConsumerIdleStrategy(String consumerIdleStrategy) {
            this.consumerIdleStrategy = consumerIdleStrategy;
        }

        public int getConsumerDrainBatch() {
            return consumerDrainBatch;
        }

        public void setConsumerDrainBatch(int consumerDrainBatch) {
            this.consumerDrainBatch = consumerDrainBatch;
        }

        public int getConsumerSpinThreshold() {
            return consumerSpinThreshold;
        }

        public void setConsumerSpinThreshold(int consumerSpinThreshold) {
            this.consumerSpinThreshold = consumerSpinThreshold;
        }

        public int getConsumerYieldThreshold() {
            return consumerYieldThreshold;
        }

        public void setConsumerYieldThreshold(int consumerYieldThreshold) {
            this.consumerYieldThreshold = consumerYieldThreshold;
        }

        public int getConsumerCursorAdvanceInterval() {
            return consumerCursorAdvanceInterval;
        }

        public void setConsumerCursorAdvanceInterval(int consumerCursorAdvanceInterval) {
            this.consumerCursorAdvanceInterval = consumerCursorAdvanceInterval;
        }

        public int getConsumerThreads() {
            return consumerThreads;
        }

        public void setConsumerThreads(int consumerThreads) {
            this.consumerThreads = consumerThreads;
        }

        public double getBackpressureWarning() {
            return backpressureWarning;
        }

        public void setBackpressureWarning(double backpressureWarning) {
            this.backpressureWarning = backpressureWarning;
        }

        public double getBackpressureCritical() {
            return backpressureCritical;
        }

        public void setBackpressureCritical(double backpressureCritical) {
            this.backpressureCritical = backpressureCritical;
        }

        public int getHandoffTimeoutMs() {
            return handoffTimeoutMs;
        }

        public void setHandoffTimeoutMs(int handoffTimeoutMs) {
            this.handoffTimeoutMs = handoffTimeoutMs;
        }
    }

    public static class Flush {
        private int coreThreads = 2;
        private int maxThreads = 4;
        private int queueCapacity = 4096;
        private int retrySyncCapMs = 200;

        public int getCoreThreads() {
            return coreThreads;
        }

        public void setCoreThreads(int coreThreads) {
            this.coreThreads = coreThreads;
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getRetrySyncCapMs() {
            return retrySyncCapMs;
        }

        public void setRetrySyncCapMs(int retrySyncCapMs) {
            this.retrySyncCapMs = retrySyncCapMs;
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
        private int decayIntervalSeconds = 0;
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

        public int getDecayIntervalSeconds() {
            return decayIntervalSeconds;
        }

        public void setDecayIntervalSeconds(int decayIntervalSeconds) {
            this.decayIntervalSeconds = decayIntervalSeconds;
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

        private int pipelineTimeoutMs = 50;

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

        public int getPipelineTimeoutMs() {
            return pipelineTimeoutMs;
        }

        public void setPipelineTimeoutMs(int pipelineTimeoutMs) {
            this.pipelineTimeoutMs = pipelineTimeoutMs;
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

    public static class Guard {
        private int maxContentLength = 32768;
        private int maxThrowableLength = 65536;

        public int getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
        }

        public int getMaxThrowableLength() {
            return maxThrowableLength;
        }

        public void setMaxThrowableLength(int maxThrowableLength) {
            this.maxThrowableLength = maxThrowableLength;
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

    public static class Handler {
        private int watchdogIntervalMs = 100;
        private int watchdogSlots = 64;

        public int getWatchdogIntervalMs() {
            return watchdogIntervalMs;
        }

        public void setWatchdogIntervalMs(int watchdogIntervalMs) {
            this.watchdogIntervalMs = watchdogIntervalMs;
        }

        public int getWatchdogSlots() {
            return watchdogSlots;
        }

        public void setWatchdogSlots(int watchdogSlots) {
            this.watchdogSlots = watchdogSlots;
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
        private Integer pipelineTimeoutMs;
        private Integer pipelineRingBufferCapacity;
        private Integer pipelineQueueCapacity;

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

        public Integer getPipelineTimeoutMs() {
            return pipelineTimeoutMs;
        }

        public void setPipelineTimeoutMs(Integer pipelineTimeoutMs) {
            this.pipelineTimeoutMs = pipelineTimeoutMs;
        }

        public Integer getPipelineQueueCapacity() {
            return pipelineQueueCapacity;
        }

        public void setPipelineQueueCapacity(Integer pipelineQueueCapacity) {
            this.pipelineQueueCapacity = pipelineQueueCapacity;
            this.pipelineRingBufferCapacity = pipelineQueueCapacity;
        }

        public Integer getPipelineRingBufferCapacity() {
            return pipelineRingBufferCapacity;
        }

        public void setPipelineRingBufferCapacity(Integer pipelineRingBufferCapacity) {
            this.pipelineRingBufferCapacity = pipelineRingBufferCapacity;
            this.pipelineQueueCapacity = pipelineRingBufferCapacity;
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

    }
}
