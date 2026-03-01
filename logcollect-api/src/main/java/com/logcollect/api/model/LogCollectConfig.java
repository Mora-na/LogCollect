package com.logcollect.api.model;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.enums.LogFramework;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.Arrays;

public class LogCollectConfig {

    private boolean enabled = true;

    // ===== 基础配置 =====
    private Class<? extends LogCollectHandler> handlerClass = LogCollectHandler.class;
    private boolean async = true;
    private String level = "INFO";
    private String[] excludeLoggerPrefixes = new String[0];
    private LogFramework logFramework = LogFramework.AUTO;
    private CollectMode collectMode = CollectMode.AUTO;
    private CollectMode effectiveCollectMode;

    // ===== 缓冲区配置 =====
    private boolean useBuffer = true;
    private int maxBufferSize = 100;
    private long maxBufferBytes = 1024L * 1024;
    private String bufferOverflowStrategy = "FLUSH_EARLY";
    private long globalBufferTotalMaxBytes = 100L * 1024 * 1024;

    // ===== 熔断降级配置 =====
    private boolean enableDegrade = true;
    private int degradeFailThreshold = 5;
    private DegradeStorage degradeStorage = DegradeStorage.FILE;
    private int recoverIntervalSeconds = 30;
    private int recoverMaxIntervalSeconds = 300;
    private int halfOpenPassCount = 3;
    private int halfOpenSuccessThreshold = 3;
    private boolean blockWhenDegradeFail = false;

    // ===== 安全防护配置 =====
    private boolean enableSanitize = true;
    private Class<? extends LogSanitizer> sanitizerClass = LogSanitizer.class;
    private boolean enableMask = true;
    private Class<? extends LogMasker> maskerClass = LogMasker.class;
    private int guardMaxContentLength = 32 * 1024;
    private int guardMaxThrowableLength = 64 * 1024;

    // ===== FILE 降级存储配置 =====
    private String degradeFileMaxTotalSize = "500MB";
    private int degradeFileTTLDays = 90;
    private boolean enableDegradeFileEncrypt = false;

    // ===== 高级配置 =====
    private int handlerTimeoutMs = 5000;
    private boolean transactionIsolation = false;
    private int maxNestingDepth = 10;

    // ===== 可观测性配置 =====
    private boolean enableMetrics = true;

    public static LogCollectConfig frameworkDefaults() {
        return new LogCollectConfig();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Class<? extends LogCollectHandler> getHandlerClass() {
        return handlerClass;
    }

    public void setHandlerClass(Class<? extends LogCollectHandler> handlerClass) {
        this.handlerClass = handlerClass;
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

    public String[] getExcludeLoggerPrefixes() {
        return Arrays.copyOf(excludeLoggerPrefixes, excludeLoggerPrefixes.length);
    }

    public void setExcludeLoggerPrefixes(String[] excludeLoggerPrefixes) {
        this.excludeLoggerPrefixes = excludeLoggerPrefixes == null
                ? new String[0]
                : Arrays.copyOf(excludeLoggerPrefixes, excludeLoggerPrefixes.length);
    }

    public LogFramework getLogFramework() {
        return logFramework;
    }

    public void setLogFramework(LogFramework logFramework) {
        this.logFramework = logFramework;
    }

    public CollectMode getCollectMode() {
        return collectMode;
    }

    public void setCollectMode(CollectMode collectMode) {
        this.collectMode = collectMode;
    }

    public CollectMode getEffectiveCollectMode() {
        return effectiveCollectMode;
    }

    public void setEffectiveCollectMode(CollectMode effectiveCollectMode) {
        this.effectiveCollectMode = effectiveCollectMode;
    }

    public boolean isUseBuffer() {
        return useBuffer;
    }

    public void setUseBuffer(boolean useBuffer) {
        this.useBuffer = useBuffer;
    }

    public int getMaxBufferSize() {
        return maxBufferSize;
    }

    public void setMaxBufferSize(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    public long getMaxBufferBytes() {
        return maxBufferBytes;
    }

    public void setMaxBufferBytes(long maxBufferBytes) {
        this.maxBufferBytes = maxBufferBytes;
    }

    public String getBufferOverflowStrategy() {
        return bufferOverflowStrategy;
    }

    public void setBufferOverflowStrategy(String bufferOverflowStrategy) {
        this.bufferOverflowStrategy = bufferOverflowStrategy;
    }

    public long getGlobalBufferTotalMaxBytes() {
        return globalBufferTotalMaxBytes;
    }

    public void setGlobalBufferTotalMaxBytes(long globalBufferTotalMaxBytes) {
        this.globalBufferTotalMaxBytes = globalBufferTotalMaxBytes;
    }

    public boolean isEnableDegrade() {
        return enableDegrade;
    }

    public void setEnableDegrade(boolean enableDegrade) {
        this.enableDegrade = enableDegrade;
    }

    public int getDegradeFailThreshold() {
        return degradeFailThreshold;
    }

    public void setDegradeFailThreshold(int degradeFailThreshold) {
        this.degradeFailThreshold = degradeFailThreshold;
    }

    public DegradeStorage getDegradeStorage() {
        return degradeStorage;
    }

    public void setDegradeStorage(DegradeStorage degradeStorage) {
        this.degradeStorage = degradeStorage;
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

    public boolean isEnableSanitize() {
        return enableSanitize;
    }

    public void setEnableSanitize(boolean enableSanitize) {
        this.enableSanitize = enableSanitize;
    }

    public Class<? extends LogSanitizer> getSanitizerClass() {
        return sanitizerClass;
    }

    public void setSanitizerClass(Class<? extends LogSanitizer> sanitizerClass) {
        this.sanitizerClass = sanitizerClass;
    }

    public boolean isEnableMask() {
        return enableMask;
    }

    public void setEnableMask(boolean enableMask) {
        this.enableMask = enableMask;
    }

    public Class<? extends LogMasker> getMaskerClass() {
        return maskerClass;
    }

    public void setMaskerClass(Class<? extends LogMasker> maskerClass) {
        this.maskerClass = maskerClass;
    }

    public int getGuardMaxContentLength() {
        return guardMaxContentLength;
    }

    public void setGuardMaxContentLength(int guardMaxContentLength) {
        this.guardMaxContentLength = guardMaxContentLength;
    }

    public int getGuardMaxThrowableLength() {
        return guardMaxThrowableLength;
    }

    public void setGuardMaxThrowableLength(int guardMaxThrowableLength) {
        this.guardMaxThrowableLength = guardMaxThrowableLength;
    }

    public String getDegradeFileMaxTotalSize() {
        return degradeFileMaxTotalSize;
    }

    public void setDegradeFileMaxTotalSize(String degradeFileMaxTotalSize) {
        this.degradeFileMaxTotalSize = degradeFileMaxTotalSize;
    }

    public int getDegradeFileTTLDays() {
        return degradeFileTTLDays;
    }

    public void setDegradeFileTTLDays(int degradeFileTTLDays) {
        this.degradeFileTTLDays = degradeFileTTLDays;
    }

    public boolean isEnableDegradeFileEncrypt() {
        return enableDegradeFileEncrypt;
    }

    public void setEnableDegradeFileEncrypt(boolean enableDegradeFileEncrypt) {
        this.enableDegradeFileEncrypt = enableDegradeFileEncrypt;
    }

    public int getHandlerTimeoutMs() {
        return handlerTimeoutMs;
    }

    public void setHandlerTimeoutMs(int handlerTimeoutMs) {
        this.handlerTimeoutMs = handlerTimeoutMs;
    }

    public boolean isTransactionIsolation() {
        return transactionIsolation;
    }

    public void setTransactionIsolation(boolean transactionIsolation) {
        this.transactionIsolation = transactionIsolation;
    }

    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    public void setMaxNestingDepth(int maxNestingDepth) {
        this.maxNestingDepth = maxNestingDepth;
    }

    public boolean isEnableMetrics() {
        return enableMetrics;
    }

    public void setEnableMetrics(boolean enableMetrics) {
        this.enableMetrics = enableMetrics;
    }

}
