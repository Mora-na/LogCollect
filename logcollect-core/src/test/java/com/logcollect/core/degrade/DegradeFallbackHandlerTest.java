package com.logcollect.core.degrade;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

class DegradeFallbackHandlerTest {

    @AfterEach
    void cleanup() throws Exception {
        resetMemoryQueue();
    }

    @Test
    void shouldThrowWhenDegradeFailsAndBlockEnabled() throws Exception {
        fillMemoryQueue();
        LogCollectContext context = newContext(true);
        Assertions.assertThrows(LogCollectDegradeException.class, () ->
                DegradeFallbackHandler.handleDegraded(
                        context, "persist_failed", Collections.singletonList("line"), "INFO"));
    }

    @Test
    void shouldNotThrowWhenDegradeFailsAndBlockDisabled() throws Exception {
        fillMemoryQueue();
        LogCollectContext context = newContext(false);
        boolean success = DegradeFallbackHandler.handleDegraded(
                context, "persist_failed", Collections.singletonList("line"), "INFO");
        Assertions.assertFalse(success);
    }

    private LogCollectContext newContext(boolean blockWhenDegradeFail) throws Exception {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableDegrade(true);
        config.setDegradeStorage(DegradeStorage.LIMITED_MEMORY);
        config.setBlockWhenDegradeFail(blockWhenDegradeFail);

        Method method = DegradeFallbackHandlerTest.class.getDeclaredMethod("marker");
        return new LogCollectContext("trace-degrade", method, new Object[0],
                config, new LogCollectHandler() {
                }, null, null, CollectMode.SINGLE);
    }

    private void fillMemoryQueue() throws Exception {
        AtomicInteger size = memoryQueueSize();
        size.set(1000);
    }

    @SuppressWarnings("unchecked")
    private void resetMemoryQueue() throws Exception {
        AtomicInteger size = memoryQueueSize();
        size.set(0);
        Field queueField = DegradeFallbackHandler.class.getDeclaredField("MEMORY_QUEUE");
        queueField.setAccessible(true);
        ConcurrentLinkedQueue<String> queue = (ConcurrentLinkedQueue<String>) queueField.get(null);
        queue.clear();
    }

    private AtomicInteger memoryQueueSize() throws Exception {
        Field sizeField = DegradeFallbackHandler.class.getDeclaredField("MEMORY_QUEUE_SIZE");
        sizeField.setAccessible(true);
        return (AtomicInteger) sizeField.get(null);
    }

    private static void marker() {
    }
}
