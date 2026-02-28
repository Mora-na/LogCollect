package com.logcollect.test;

import com.logcollect.api.model.LogEntry;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.Assertions;

import java.util.List;

public final class LogCollectAssertions {
    private LogCollectAssertions() {}

    public static void assertLogContains(InMemoryLogCollectHandler handler, String traceId, String content) {
        List<LogEntry> logs = handler.getLogsByTraceId(traceId);
        boolean found = false;
        if (logs != null) {
            for (LogEntry entry : logs) {
                if (entry.getContent() != null && entry.getContent().contains(content)) {
                    found = true;
                    break;
                }
            }
        }
        Assertions.assertTrue(found, "Expected log content not found");
    }

    public static void assertLogCount(InMemoryLogCollectHandler handler, String traceId, int expected) {
        List<LogEntry> logs = handler.getLogsByTraceId(traceId);
        int actual = logs == null ? 0 : logs.size();
        Assertions.assertEquals(expected, actual);
    }

    public static void assertMasked(InMemoryLogCollectHandler handler, String traceId, String rawPII) {
        List<LogEntry> logs = handler.getLogsByTraceId(traceId);
        if (logs == null) {
            return;
        }
        for (LogEntry entry : logs) {
            Assertions.assertFalse(entry.getContent() != null && entry.getContent().contains(rawPII));
        }
    }

    public static void assertNoContextLeak() {
        Assertions.assertNull(LogCollectContextManager.current());
    }
}
