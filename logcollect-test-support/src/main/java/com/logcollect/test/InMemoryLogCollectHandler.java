package com.logcollect.test;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryLogCollectHandler implements LogCollectHandler {
    private final ConcurrentMap<String, List<LogEntry>> logs = new ConcurrentHashMap<String, List<LogEntry>>();

    @Override
    public void before(LogCollectContext context) {
        if (context == null) {
            return;
        }
        logs.put(context.getTraceId(), new CopyOnWriteArrayList<LogEntry>());
        context.setBusinessId(context.getTraceId());
    }

    @Override
    public void appendLog(LogCollectContext context, LogEntry entry) {
        if (context == null || entry == null) {
            return;
        }
        logs.computeIfAbsent(context.getTraceId(), k -> new CopyOnWriteArrayList<LogEntry>())
                .add(entry);
    }

    @Override
    public void after(LogCollectContext context) {
    }

    public List<LogEntry> getLogsByTraceId(String traceId) {
        return logs.get(traceId);
    }

    public List<LogEntry> getAllLogs() {
        List<LogEntry> all = new CopyOnWriteArrayList<LogEntry>();
        for (List<LogEntry> list : logs.values()) {
            all.addAll(list);
        }
        return all;
    }

    public void clear() {
        logs.clear();
    }
}
