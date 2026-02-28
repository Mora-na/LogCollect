package com.logcollect.api.model;

import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.enums.LogFramework;

public class LogCollectConfig {
    private boolean enabled = true;
    private boolean async = true;
    private String level = "INFO";
    private boolean useBuffer = true;
    private int maxBufferSize = 100;
    private long maxBufferBytes = 1024 * 1024L;
    private int degradeFileTTLDays = 90;
    private int handlerTimeoutMs = 5000;
    private boolean transactionIsolation = false;
    private int maxNestingDepth = 10;
    private int degradeWindowSeconds = 10;
    private long globalBufferTotalMaxBytes = 100L * 1024 * 1024;
    private int failureThreshold = 5;
    private int halfOpenSuccessThreshold = 2;
    private int halfOpenPassCount = 3;
    private int recoverIntervalSeconds = 10;
    private int maxRecoverIntervalSeconds = 60;
    private DegradeStorage degradeStorage = DegradeStorage.FILE;
    private LogFramework logFramework = LogFramework.UNKNOWN;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public boolean isUseBuffer() { return useBuffer; }
    public void setUseBuffer(boolean useBuffer) { this.useBuffer = useBuffer; }

    public int getMaxBufferSize() { return maxBufferSize; }
    public void setMaxBufferSize(int maxBufferSize) { this.maxBufferSize = maxBufferSize; }

    public long getMaxBufferBytes() { return maxBufferBytes; }
    public void setMaxBufferBytes(long maxBufferBytes) { this.maxBufferBytes = maxBufferBytes; }

    public int getDegradeFileTTLDays() { return degradeFileTTLDays; }
    public void setDegradeFileTTLDays(int degradeFileTTLDays) { this.degradeFileTTLDays = degradeFileTTLDays; }

    public int getHandlerTimeoutMs() { return handlerTimeoutMs; }
    public void setHandlerTimeoutMs(int handlerTimeoutMs) { this.handlerTimeoutMs = handlerTimeoutMs; }

    public boolean isTransactionIsolation() { return transactionIsolation; }
    public void setTransactionIsolation(boolean transactionIsolation) { this.transactionIsolation = transactionIsolation; }

    public int getMaxNestingDepth() { return maxNestingDepth; }
    public void setMaxNestingDepth(int maxNestingDepth) { this.maxNestingDepth = maxNestingDepth; }

    public int getDegradeWindowSeconds() { return degradeWindowSeconds; }
    public void setDegradeWindowSeconds(int degradeWindowSeconds) { this.degradeWindowSeconds = degradeWindowSeconds; }

    public long getGlobalBufferTotalMaxBytes() { return globalBufferTotalMaxBytes; }
    public void setGlobalBufferTotalMaxBytes(long globalBufferTotalMaxBytes) { this.globalBufferTotalMaxBytes = globalBufferTotalMaxBytes; }

    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }

    public int getHalfOpenSuccessThreshold() { return halfOpenSuccessThreshold; }
    public void setHalfOpenSuccessThreshold(int halfOpenSuccessThreshold) { this.halfOpenSuccessThreshold = halfOpenSuccessThreshold; }

    public int getHalfOpenPassCount() { return halfOpenPassCount; }
    public void setHalfOpenPassCount(int halfOpenPassCount) { this.halfOpenPassCount = halfOpenPassCount; }

    public int getRecoverIntervalSeconds() { return recoverIntervalSeconds; }
    public void setRecoverIntervalSeconds(int recoverIntervalSeconds) { this.recoverIntervalSeconds = recoverIntervalSeconds; }

    public int getMaxRecoverIntervalSeconds() { return maxRecoverIntervalSeconds; }
    public void setMaxRecoverIntervalSeconds(int maxRecoverIntervalSeconds) { this.maxRecoverIntervalSeconds = maxRecoverIntervalSeconds; }

    public DegradeStorage getDegradeStorage() { return degradeStorage; }
    public void setDegradeStorage(DegradeStorage degradeStorage) { this.degradeStorage = degradeStorage; }

    public LogFramework getLogFramework() { return logFramework; }
    public void setLogFramework(LogFramework logFramework) { this.logFramework = logFramework; }
}
