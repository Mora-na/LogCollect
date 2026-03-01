package com.logcollect.core.degrade;

import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class DegradeFallbackHandler {

    private static final int MAX_MEMORY_QUEUE_SIZE = 1000;
    private static final ConcurrentLinkedQueue<String> MEMORY_QUEUE = new ConcurrentLinkedQueue<String>();
    private static final AtomicInteger MEMORY_QUEUE_SIZE = new AtomicInteger(0);

    private DegradeFallbackHandler() {
    }

    public static boolean handleDegraded(LogCollectContext context, String reason, List<String> lines, String level) {
        if (context == null || context.getConfig() == null) {
            return false;
        }

        LogCollectConfig config = context.getConfig();
        if (!config.isEnableDegrade()) {
            return false;
        }

        DegradeStorage storage = config.getDegradeStorage();
        try {
            if (storage == DegradeStorage.FILE) {
                return degradeToFile(context, lines);
            }
            if (storage == DegradeStorage.LIMITED_MEMORY) {
                return degradeToMemory(context, lines);
            }
            if (storage == DegradeStorage.DISCARD_NON_ERROR) {
                return "ERROR".equalsIgnoreCase(level) || "FATAL".equalsIgnoreCase(level);
            }
            if (storage == DegradeStorage.DISCARD_ALL) {
                return false;
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Degrade fallback failed", t);
        }
        return false;
    }

    private static boolean degradeToFile(LogCollectContext context, List<String> lines) {
        Object fileManagerObj = context.getAttribute("__degradeFileManager");
        if (!(fileManagerObj instanceof DegradeFileManager)) {
            return false;
        }
        DegradeFileManager fileManager = (DegradeFileManager) fileManagerObj;
        if (!fileManager.isInitialized()) {
            return false;
        }
        List<String> copy = lines == null ? new ArrayList<String>() : lines;
        fileManager.write(context.getTraceId(), copy);
        return true;
    }

    private static boolean degradeToMemory(LogCollectContext context, List<String> lines) {
        if (MEMORY_QUEUE_SIZE.get() >= MAX_MEMORY_QUEUE_SIZE) {
            return false;
        }
        String value;
        if (lines == null || lines.isEmpty()) {
            value = context.getTraceId() + "|" + context.getMethodSignature();
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            value = sb.toString();
        }
        MEMORY_QUEUE.offer(value);
        MEMORY_QUEUE_SIZE.incrementAndGet();
        return true;
    }
}
