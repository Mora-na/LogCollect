package com.logcollect.config.apollo;

import com.logcollect.api.config.LogCollectConfigChangeListener;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.config.Refreshable;

import java.util.Collections;
import java.util.Map;

public class ApolloLogCollectConfigSource implements LogCollectConfigSource, Refreshable {
    @Override
    public Map<String, String> getProperties(String prefix) {
        return Collections.emptyMap();
    }

    @Override
    public void addChangeListener(LogCollectConfigChangeListener listener) {
    }

    @Override
    public String getType() {
        return "apollo";
    }

    @Override
    public void refresh() {
    }
}
