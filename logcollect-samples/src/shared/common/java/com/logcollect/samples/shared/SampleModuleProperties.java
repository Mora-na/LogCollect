package com.logcollect.samples.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sample")
public class SampleModuleProperties {
    private String moduleId;
    private String bootVersion;
    private String loggingFramework;
    private boolean autoRun = true;
    private boolean exitAfterRun;

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
    }

    public String getBootVersion() {
        return bootVersion;
    }

    public void setBootVersion(String bootVersion) {
        this.bootVersion = bootVersion;
    }

    public String getLoggingFramework() {
        return loggingFramework;
    }

    public void setLoggingFramework(String loggingFramework) {
        this.loggingFramework = loggingFramework;
    }

    public boolean isAutoRun() {
        return autoRun;
    }

    public void setAutoRun(boolean autoRun) {
        this.autoRun = autoRun;
    }

    public boolean isExitAfterRun() {
        return exitAfterRun;
    }

    public void setExitAfterRun(boolean exitAfterRun) {
        this.exitAfterRun = exitAfterRun;
    }

    public String describe() {
        return moduleId + " | Boot " + bootVersion + " | " + loggingFramework;
    }
}
