package com.logcollect.core.pipeline;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.SingleWriterBuffer;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PipelineConsumerBranchTest extends CoreUnitTestBase {

    @Test
    void closeAndFlush_drainsQueueAndMarksContextClosed() {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectConfig config = baseConfig();
        SingleWriterBuffer buffer = new SingleWriterBuffer(16, parseBytes("1MB"), 16);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, buffer, null);
        context.setPipelineQueue(new PipelineQueue(32, 0.7d, 0.9d));
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("16MB")));

        PipelineQueue queue = (PipelineQueue) context.getPipelineQueue();
        queue.offer(raw(context, "queue-1", "INFO"));
        queue.offer(raw(context, "queue-2", "WARN"));

        PipelineConsumer consumer = new PipelineConsumer("ut-consumer", null);
        consumer.assign(context);
        consumer.closeAndFlush(context, TimeUnit.MILLISECONDS.toNanos(10));

        assertThat(context.isClosed()).isTrue();
        assertThat(buffer.isClosed()).isTrue();
        verify(handler, atLeast(1)).appendLog(eq(context), any(LogEntry.class));
    }

    @Test
    void processOneRecord_branches_coverFilterTotalLimitClosingAndBufferClosed() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(false, true, true, true);
        LogCollectConfig config = baseConfig();
        SingleWriterBuffer buffer = new SingleWriterBuffer(8, parseBytes("1MB"), 8);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, buffer, null);
        context.setPipelineQueue(new PipelineQueue(8, 0.7d, 0.9d));
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("4MB")));

        PipelineConsumer consumer = new PipelineConsumer("ut-consumer", null);
        Method processOne = method("processOneRecord", LogCollectContext.class, RawLogRecord.class, boolean.class);

        processOne.invoke(consumer, context, raw(context, "filtered", "INFO"), false);
        assertThat(context.getTotalDiscardedCount()).isGreaterThan(0);

        config.setMaxTotalCollect(1);
        context.incrementCollectedCount();
        processOne.invoke(consumer, context, raw(context, "limit", "INFO"), false);
        assertThat(context.getTotalDiscardedCount()).isGreaterThan(1);
        config.setMaxTotalCollect(100000);

        context.markClosing();
        processOne.invoke(consumer, context, raw(context, "closing-requeue", "INFO"), false);
        assertThat(((PipelineQueue) context.getPipelineQueue()).size()).isGreaterThan(0);

        buffer.markClosed();
        processOne.invoke(consumer, context, raw(context, "late", "WARN"), true);
        assertThat(context.getTotalDiscardedCount()).isGreaterThan(1);
    }

    @Test
    void processOneRecord_oversizedAndGlobalAllocateBranches() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectConfig config = baseConfig();
        SingleWriterBuffer buffer = new SingleWriterBuffer(8, 64, 8);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, buffer, null);
        context.setPipelineQueue(new PipelineQueue(8, 0.7d, 0.9d));
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("1MB")));

        PipelineConsumer consumer = new PipelineConsumer("ut-consumer", null);
        Method processOne = method("processOneRecord", LogCollectContext.class, RawLogRecord.class, boolean.class);

        processOne.invoke(consumer, context, raw(context, repeat("x", 256), "ERROR"), false);
        verify(handler, atLeast(1)).appendLog(eq(context), any(LogEntry.class));

        GlobalBufferMemoryManager exhausted = new GlobalBufferMemoryManager(parseBytes("1MB"));
        assertThat(exhausted.forceAllocate(parseBytes("1MB"))).isTrue();
        context.setAttribute("__globalBufferManager", exhausted);
        long collectedBefore = context.getTotalCollectedCount();
        processOne.invoke(consumer, context, raw(context, "low-priority-over-quota", "INFO"), false);
        assertThat(context.getTotalCollectedCount()).isEqualTo(collectedBefore);
    }

    @Test
    void helperMethods_coverMetricsAndUtilityBranches() throws Exception {
        PipelineConsumer consumer = new PipelineConsumer("ut-consumer", null);
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);

        LogCollectConfig config = baseConfig();
        SingleWriterBuffer buffer = new SingleWriterBuffer(4, parseBytes("1MB"), 4);
        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, buffer, null);
        context.setPipelineQueue(new PipelineQueue(16, 0.7d, 0.9d));

        Method joinContents = method("joinContents", List.class);
        assertThat((String) joinContents.invoke(consumer, Collections.emptyList())).isEmpty();

        List<LogEntry> entries = new ArrayList<LogEntry>();
        entries.add(createTestEntry("a", "INFO"));
        entries.add(createTestEntry("b", "WARN"));
        assertThat(((String) joinContents.invoke(consumer, entries))).contains("a").contains("b");

        Method estimateBatch = method("estimateBatchBytes", List.class);
        assertThat((Long) estimateBatch.invoke(consumer, entries)).isGreaterThan(0L);

        Method higherLevel = method("higherLevel", String.class, String.class);
        assertThat((String) higherLevel.invoke(consumer, "INFO", "ERROR")).isEqualTo("ERROR");

        Method levelRank = method("levelRank", String.class);
        assertThat((Integer) levelRank.invoke(consumer, "WARN")).isEqualTo(3);

        Method idleRatio = method("idleRatio");
        assertThat((Double) idleRatio.invoke(consumer)).isGreaterThanOrEqualTo(0.0d);

        Method createSecurityMetrics = method("createSecurityMetrics", String.class, com.logcollect.api.metrics.LogCollectMetrics.class);
        LogCollectMetrics metrics = mock(LogCollectMetrics.class);
        SecurityPipeline.SecurityMetrics securityMetrics = (SecurityPipeline.SecurityMetrics)
                createSecurityMetrics.invoke(consumer, context.getMethodSignature(), metrics);
        securityMetrics.onContentSanitized();
        securityMetrics.onThrowableSanitized();
        securityMetrics.onContentMasked();
        securityMetrics.onThrowableMasked();
        securityMetrics.onFastPathHit();
        securityMetrics.onPipelineTimeout("step");
        verify(metrics, times(2)).incrementSanitizeHits(context.getMethodSignature());
        verify(metrics, times(2)).incrementMaskHits(context.getMethodSignature());
        verify(metrics, times(1)).incrementFastPathHits(context.getMethodSignature());
        verify(metrics, times(1)).incrementPipelineTimeout(context.getMethodSignature(), "step");
    }

    @Test
    void run_loopProcessesAssignedContextAndStopsOnShutdown() throws Exception {
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        AtomicInteger appendCalls = new AtomicInteger(0);
        doAnswer(inv -> {
            appendCalls.incrementAndGet();
            return null;
        }).when(handler).appendLog(any(LogCollectContext.class), any(LogEntry.class));

        LogCollectConfig config = baseConfig();
        SingleWriterBuffer buffer = new SingleWriterBuffer(1, parseBytes("1MB"), 8);
        LogCollectContext context = createTestContext(config, handler, CollectMode.SINGLE, buffer, null);
        PipelineQueue queue = new PipelineQueue(16, 0.7d, 0.9d);
        context.setPipelineQueue(queue);
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));

        PipelineConsumer consumer = new PipelineConsumer("ut-loop", null);
        consumer.assign(context);
        queue.offer(raw(context, "loop-msg", "INFO"));

        Thread worker = new Thread(consumer, "ut-pipeline-consumer");
        worker.start();
        Thread.sleep(30L);
        consumer.shutdown();
        worker.interrupt();
        worker.join(500L);

        assertThat(appendCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(worker.isAlive()).isFalse();
    }

    private LogCollectConfig baseConfig() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        config.setEnableSanitize(false);
        config.setEnableMask(false);
        config.setEnableDegrade(true);
        config.setUseBuffer(true);
        return config;
    }

    private RawLogRecord raw(LogCollectContext context, String content, String level) {
        return new RawLogRecord(
                content,
                null,
                level,
                "ut.logger",
                "main",
                System.currentTimeMillis(),
                Collections.emptyMap(),
                context);
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        Method method = PipelineConsumer.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
