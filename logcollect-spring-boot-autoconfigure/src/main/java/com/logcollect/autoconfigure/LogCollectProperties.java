package com.logcollect.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "logcollect")
public class LogCollectProperties {
    private boolean enabled = true;
    private long globalBufferTotalMaxBytes = 100L * 1024 * 1024;
    private String internalLogLevel = "INFO";
    private int globalHandlerTimeoutMs = 5000;
    private int globalMaxNestingDepth = 10;
    private int globalDegradeWindowSeconds = 10;

    @NestedConfigurationProperty
    private final Config config = new Config();

    @NestedConfigurationProperty
    private final Degrade degrade = new Degrade();

    @NestedConfigurationProperty
    private final Logging logging = new Logging();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getGlobalBufferTotalMaxBytes() { return globalBufferTotalMaxBytes; }
    public void setGlobalBufferTotalMaxBytes(long globalBufferTotalMaxBytes) { this.globalBufferTotalMaxBytes = globalBufferTotalMaxBytes; }

    public String getInternalLogLevel() { return internalLogLevel; }
    public void setInternalLogLevel(String internalLogLevel) { this.internalLogLevel = internalLogLevel; }

    public int getGlobalHandlerTimeoutMs() { return globalHandlerTimeoutMs; }
    public void setGlobalHandlerTimeoutMs(int globalHandlerTimeoutMs) { this.globalHandlerTimeoutMs = globalHandlerTimeoutMs; }

    public int getGlobalMaxNestingDepth() { return globalMaxNestingDepth; }
    public void setGlobalMaxNestingDepth(int globalMaxNestingDepth) { this.globalMaxNestingDepth = globalMaxNestingDepth; }

    public int getGlobalDegradeWindowSeconds() { return globalDegradeWindowSeconds; }
    public void setGlobalDegradeWindowSeconds(int globalDegradeWindowSeconds) { this.globalDegradeWindowSeconds = globalDegradeWindowSeconds; }

    public Config getConfig() { return config; }
    public Degrade getDegrade() { return degrade; }
    public Logging getLogging() { return logging; }

    public static class Config {
        @NestedConfigurationProperty
        private final LocalCache localCache = new LocalCache();

        public LocalCache getLocalCache() { return localCache; }
    }

    public static class LocalCache {
        private boolean enabled = true;
        private String path = System.getProperty("user.home") + "/.logcollect/config-cache.properties";
        private int maxAgeDays = 7;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public int getMaxAgeDays() { return maxAgeDays; }
        public void setMaxAgeDays(int maxAgeDays) { this.maxAgeDays = maxAgeDays; }
    }

    public static class Degrade {
        @NestedConfigurationProperty
        private final File file = new File();

        public File getFile() { return file; }
    }

    public static class Logging {
        private boolean autoRegisterAppender = true;
        private String appenderName = "LOG_COLLECT";

        public boolean isAutoRegisterAppender() { return autoRegisterAppender; }
        public void setAutoRegisterAppender(boolean autoRegisterAppender) {
            this.autoRegisterAppender = autoRegisterAppender;
        }

        public String getAppenderName() { return appenderName; }
        public void setAppenderName(String appenderName) { this.appenderName = appenderName; }
    }

    public static class File {
        private boolean encryptEnabled = false;

        public boolean isEncryptEnabled() { return encryptEnabled; }
        public void setEncryptEnabled(boolean encryptEnabled) { this.encryptEnabled = encryptEnabled; }
    }
}
