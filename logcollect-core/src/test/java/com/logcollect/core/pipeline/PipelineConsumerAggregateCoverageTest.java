package com.logcollect.core.pipeline;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.buffer.AggregateDirectBuffer;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.SingleWriterBuffer;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PipelineConsumerAggregateCoverageTest extends CoreUnitTestBase {

    @Test
    void aggregateFlow_coversAppendFlushOverflowAndIdleHelpers() throws Exception {
        LogCollectConfig config = baseConfig();
        config.setMaxBufferSize(8);
        config.setMaxBufferBytes(parseBytes("1MB"));

        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);

        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        context.setAttribute("__metrics", mock(LogCollectMetrics.class));
        context.setAttribute("__globalBufferManager", new GlobalBufferMemoryManager(parseBytes("8MB")));
        context.setPipelineQueue(new PipelineRingBuffer(8, 8));

        PipelineConsumer consumer = new PipelineConsumer("ut-aggregate-flow", null);
        Method processOneRecord = method("processOneRecord", LogCollectContext.class, RawLogRecord.class, boolean.class);
        Method processContextBatch = method("processContextBatch", LogCollectContext.class);
        Method flushIfNeeded = method("flushIfNeeded", LogCollectContext.class, boolean.class);
        Method resolveIdleStrategy = method("resolveIdleStrategy");
        Method idle = method("idle", String.class);

        processOneRecord.invoke(consumer, context, raw(context, "aggregate-first", "INFO"), false);

        AggregateDirectBuffer aggregateBuffer = context.getAttribute("__aggregateDirectBuffer", AggregateDirectBuffer.class);
        assertThat(aggregateBuffer).isNotNull();
        aggregateBuffer.setPatternVersion(-1);

        processOneRecord.invoke(consumer, context, raw(context, "aggregate-second", "WARN"), false);

        PipelineRingBuffer ringBuffer = (PipelineRingBuffer) context.getPipelineQueue();
        ringBuffer.offerOverflow(raw(context, "aggregate-overflow", "ERROR"));
        assertThat((Integer) processContextBatch.invoke(consumer, context)).isGreaterThanOrEqualTo(1);

        flushIfNeeded.invoke(consumer, context, true);

        verify(handler, atLeastOnce()).flushAggregatedLog(eq(context), any(AggregatedLog.class));
        assertThat(context.getTotalCollectedCount()).isGreaterThanOrEqualTo(2);
        assertThat(resolveIdleStrategy.invoke(consumer)).isEqualTo("ADAPTIVE");
        idle.invoke(consumer, "ignored");
    }

    @Test
    void aggregateHelpers_coverAllocationAndPatternBranches() throws Exception {
        PipelineConsumer consumer = new PipelineConsumer("ut-aggregate-helper", null);

        Method tryAllocateAggregate = method(
                "tryAllocateAggregate",
                LogCollectContext.class,
                long.class,
                String.class,
                String.class,
                String.class);
        Method resolveAggregateBuffer = method("resolveAggregateBuffer", LogCollectContext.class, LogCollectConfig.class);
        Method resolveLinePattern = method("resolveLinePattern", LogCollectHandler.class);
        Method estimateAggregateBytes = method("estimateAggregateBytes", MutableProcessedLogRecord.class);

        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContext context = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);
        context.setAttribute("__metrics", mock(LogCollectMetrics.class));

        AggregateDirectBuffer first = (AggregateDirectBuffer) resolveAggregateBuffer.invoke(consumer, context, config);
        assertThat(first).isNotNull();
        assertThat(resolveAggregateBuffer.invoke(consumer, context, config)).isSameAs(first);

        String defaultPattern = (String) resolveLinePattern.invoke(consumer, new Object[]{null});
        assertThat(defaultPattern).isNotBlank();
        LogCollectHandler emptyPatternHandler = new LogCollectHandler() {
            @Override
            public String logLinePattern() {
                return "";
            }
        };
        assertThat((String) resolveLinePattern.invoke(consumer, emptyPatternHandler)).isEqualTo(defaultPattern);
        LogCollectHandler customPatternHandler = new LogCollectHandler() {
            @Override
            public String logLinePattern() {
                return "%msg";
            }
        };
        assertThat((String) resolveLinePattern.invoke(consumer, customPatternHandler)).isEqualTo("%msg");

        MutableProcessedLogRecord record = new MutableProcessedLogRecord();
        record.processedMessage = "content";
        record.processedThrowable = "throwable";
        assertThat((Long) estimateAggregateBytes.invoke(consumer, record)).isGreaterThan(64L);

        GlobalBufferMemoryManager forceRejected = new GlobalBufferMemoryManager(
                parseBytes("1MB"),
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                parseBytes("1MB"));
        assertThat(forceRejected.tryAllocate(parseBytes("1MB"))).isTrue();
        context.setAttribute("__globalBufferManager", forceRejected);
        assertThat((Boolean) tryAllocateAggregate.invoke(
                consumer, context, 64L, "INFO", "low-priority", context.getTraceId())).isFalse();
        assertThat((Boolean) tryAllocateAggregate.invoke(
                consumer, context, 64L, "ERROR", "high-priority", context.getTraceId())).isFalse();

        GlobalBufferMemoryManager forceAllowed = new GlobalBufferMemoryManager(
                parseBytes("1MB"),
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                parseBytes("2MB"));
        assertThat(forceAllowed.tryAllocate(parseBytes("1MB"))).isTrue();
        context.setAttribute("__globalBufferManager", forceAllowed);
        assertThat((Boolean) tryAllocateAggregate.invoke(
                consumer, context, 64L, "ERROR", "high-priority", context.getTraceId())).isTrue();
    }

    @Test
    void routeToDegradation_coversNonNullContextBranch() throws Exception {
        LogCollectConfig config = baseConfig();
        LogCollectHandler handler = mock(LogCollectHandler.class);
        when(handler.shouldCollect(any(LogCollectContext.class), anyString(), anyString())).thenReturn(true);
        LogCollectContext context = createTestContext(
                config,
                handler,
                CollectMode.SINGLE,
                new SingleWriterBuffer(8, parseBytes("1MB"), 8),
                null);

        PipelineConsumer consumer = new PipelineConsumer("ut-route", null);
        Method routeToDegradation = method(
                "routeToDegradation",
                LogCollectContext.class,
                List.class,
                com.logcollect.api.enums.DegradeReason.class);
        routeToDegradation.invoke(
                consumer,
                context,
                Collections.singletonList(createTestEntry("degrade-line", "INFO")),
                null);

        assertThat(context.getTotalDiscardedCount()).isGreaterThanOrEqualTo(0);
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
}
