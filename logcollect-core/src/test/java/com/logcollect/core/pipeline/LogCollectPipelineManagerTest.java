package com.logcollect.core.pipeline;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.SingleWriterBuffer;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogCollectPipelineManagerTest extends CoreUnitTestBase {

    @Test
    void lifecycle_registerCloseStartShutdown_coverBranches() throws Exception {
        LogCollectPipelineManager manager = new LogCollectPipelineManager(0, null);
        manager.start();
        manager.start();

        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);

        LogCollectConfig config = baseConfig();
        SingleWriterBuffer buffer = new SingleWriterBuffer(1, parseBytes("1MB"), 8);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, buffer, null);
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));

        manager.registerContext(null);
        manager.registerContext(context);

        PipelineRingBuffer ringBuffer = (PipelineRingBuffer) context.getPipelineQueue();
        long seq = ringBuffer.tryClaim();
        MutableRawLogRecord slot = ringBuffer.getSlot(seq);
        slot.populate(
                "manager-msg",
                "INFO",
                "ut.logger",
                "main",
                System.currentTimeMillis(),
                context.getTraceId(),
                null,
                java.util.Collections.emptyMap());
        ringBuffer.publish(seq);
        Thread.sleep(20L);

        manager.closeContext(null);
        manager.closeContext(context);
        assertThat(context.isClosed()).isTrue();

        LogCollectContext fallbackContext = createTestContext(config, handler, CollectMode.SINGLE,
                new SingleWriterBuffer(2, parseBytes("1MB"), 4), null);
        fallbackContext.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));
        manager.registerContext(fallbackContext);
        fallbackContext.setPipelineConsumer("invalid-consumer");
        manager.closeContext(fallbackContext);
        assertThat(fallbackContext.isClosed()).isTrue();

        manager.shutdown(1L);
        manager.shutdown(1L);
    }

    private LogCollectConfig baseConfig() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        config.setEnableSanitize(false);
        config.setEnableMask(false);
        config.setUseBuffer(true);
        config.setPipelineRingBufferCapacity(8);
        config.setPipelineBackpressureWarning(0.6d);
        config.setPipelineBackpressureCritical(0.9d);
        config.setPipelineHandoffTimeoutMs(1);
        return config;
    }
}
