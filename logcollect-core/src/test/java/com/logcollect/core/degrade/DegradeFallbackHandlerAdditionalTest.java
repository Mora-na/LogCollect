package com.logcollect.core.degrade;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradeFallbackHandlerAdditionalTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() throws Exception {
        resetMemoryQueue();
    }

    @Test
    void handleDegraded_nullContextOrDisabled_returnsFalse() throws Exception {
        assertThat(DegradeFallbackHandler.handleDegraded(null, "r", Collections.singletonList("x"), "INFO"))
                .isFalse();

        LogCollectContext disabled = newContext(DegradeStorage.FILE, false, false);
        assertThat(DegradeFallbackHandler.handleDegraded(disabled, "r", Collections.singletonList("x"), "INFO"))
                .isFalse();
    }

    @Test
    void handleDegraded_discardAll_returnsTrue() throws Exception {
        LogCollectContext context = newContext(DegradeStorage.DISCARD_ALL, true, false);
        assertThat(DegradeFallbackHandler.handleDegraded(context, "r", Collections.singletonList("x"), "INFO"))
                .isTrue();
    }

    @Test
    void handleDegraded_discardNonError_infoReturnsTrue_errorFallbackToMemory() throws Exception {
        LogCollectContext infoContext = newContext(DegradeStorage.DISCARD_NON_ERROR, true, false);
        assertThat(DegradeFallbackHandler.handleDegraded(infoContext, "r", Collections.singletonList("x"), "INFO"))
                .isTrue();

        LogCollectContext errorContext = newContext(DegradeStorage.DISCARD_NON_ERROR, true, false);
        assertThat(DegradeFallbackHandler.handleDegraded(errorContext, "r", Collections.singletonList("x"), "ERROR"))
                .isTrue();
    }

    @Test
    void handleDegraded_fileStorage_withInitializedFileManager_succeeds() throws Exception {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 10 * 1024 * 1024, 7, null);
        manager.initialize();

        LogCollectContext context = newContext(DegradeStorage.FILE, true, false);
        context.setAttribute("__degradeFileManager", manager);

        boolean success = DegradeFallbackHandler.handleDegraded(
                context, "persist_failed", Collections.singletonList("line"), "WARN");

        assertThat(success).isTrue();
        waitUntilFileCountAtLeast(manager, 1, 2000L);
        assertThat(manager.getFileCount()).isEqualTo(1);
    }

    @Test
    void handleDegraded_memoryFullAndFileFailure_blockingModeThrowsWithCause() throws Exception {
        fillMemoryQueue();
        LogCollectContext context = newContext(DegradeStorage.FILE, true, true);
        context.setAttribute("__degradeFileManager", new ExplodingDegradeFileManager());

        assertThatThrownBy(() -> DegradeFallbackHandler.handleDegraded(
                context, "persist_failed", Collections.singletonList("line"), "ERROR"))
                .isInstanceOf(LogCollectDegradeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    private LogCollectContext newContext(DegradeStorage storage,
                                         boolean enableDegrade,
                                         boolean blockWhenDegradeFail) throws Exception {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableDegrade(enableDegrade);
        config.setDegradeStorage(storage);
        config.setBlockWhenDegradeFail(blockWhenDegradeFail);

        Method method = DegradeFallbackHandlerAdditionalTest.class.getDeclaredMethod("marker");
        return new LogCollectContext("trace-fallback", method, new Object[0],
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

    private void waitUntilFileCountAtLeast(DegradeFileManager manager, long expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (manager.getFileCount() >= expected) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    @SuppressWarnings("unused")
    private static void marker() {
    }

    private static final class ExplodingDegradeFileManager extends DegradeFileManager {
        private ExplodingDegradeFileManager() {
            super(null, 1024, 1, null);
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public void write(String traceId, List<String> logLines) {
            throw new RuntimeException("write failed");
        }
    }
}
