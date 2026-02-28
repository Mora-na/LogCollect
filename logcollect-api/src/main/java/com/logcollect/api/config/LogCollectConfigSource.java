package com.logcollect.api.config;

import java.util.Map;

public interface LogCollectConfigSource {
    Map<String, String> getProperties(String prefix);
    void addChangeListener(LogCollectConfigChangeListener listener);
    String getType();
    default boolean isAvailable() { return true; }
    default int getOrder() { return 100; }
}
