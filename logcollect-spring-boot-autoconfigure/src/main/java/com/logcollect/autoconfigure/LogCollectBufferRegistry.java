package com.logcollect.autoconfigure;

import com.logcollect.core.buffer.LogCollectBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LogCollectBufferRegistry {
    private final Set<LogCollectBuffer> buffers = ConcurrentHashMap.newKeySet();

    public void register(LogCollectBuffer buffer) {
        if (buffer != null) {
            buffers.add(buffer);
        }
    }

    public void unregister(LogCollectBuffer buffer) {
        if (buffer != null) {
            buffers.remove(buffer);
        }
    }

    public List<LogCollectBuffer> all() {
        return new ArrayList<LogCollectBuffer>(buffers);
    }
}
