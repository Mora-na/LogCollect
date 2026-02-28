package com.logcollect.api.config;

import java.util.Map;

public interface LogCollectConfigChangeListener {
    void onChange(Map<String, String> newProperties);
}
