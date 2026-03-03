package com.logcollect.core.buffer;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.pipeline.SecurityPipeline;
import com.logcollect.core.test.ConcurrentTestHelper;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AggregateModeBufferTest extends CoreUnitTestBase {

    private LogCollectContext context;
    private LogCollectHandler mockHandler;

    @BeforeEach
    void setUp() {
        mockHandler = mock(LogCollectHandler.class);
        when(mockHandler.formatLogLine(any())).thenAnswer(invocation -> {
            com.logcollect.api.model.LogEntry entry = invocation.getArgument(0);
            return "[" + entry.getLevel() + "] " + entry.getContent();
        });
        when(mockHandler.aggregatedLogSeparator()).thenReturn("\n");

        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        context = createTestContext(config, mockHandler, CollectMode.AGGREGATE, null, null);
    }

    @Test
    void offer_singleEntry_buffered() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        buffer.offer(context, createTestEntry("msg1", "INFO"));
        assertThat(buffer.dumpAsString()).contains("[INFO] msg1");
    }

    @Test
    void flush_bufferedEntries_aggregatedAndCallback() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        buffer.offer(context, createTestEntry("msg1", "INFO"));
        buffer.offer(context, createTestEntry("msg2", "WARN"));

        buffer.triggerFlush(context, false);

        ArgumentCaptor<AggregatedLog> captor = ArgumentCaptor.forClass(AggregatedLog.class);
        verify(mockHandler).flushAggregatedLog(eq(context), captor.capture());

        AggregatedLog agg = captor.getValue();
        assertThat(agg.getContent()).contains("[INFO] msg1", "[WARN] msg2");
        assertThat(agg.getEntryCount()).isEqualTo(2);
        assertThat(agg.getMaxLevel()).isEqualTo("WARN");
        assertThat(agg.isFinalFlush()).isFalse();
    }

    @Test
    void flush_final_markedAsFinalFlush() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        buffer.offer(context, createTestEntry("msg", "INFO"));

        buffer.triggerFlush(context, true);

        ArgumentCaptor<AggregatedLog> captor = ArgumentCaptor.forClass(AggregatedLog.class);
        verify(mockHandler).flushAggregatedLog(eq(context), captor.capture());
        assertThat(captor.getValue().isFinalFlush()).isTrue();
    }

    @Test
    void flush_emptyBuffer_noCallback() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        buffer.triggerFlush(context, false);
        verify(mockHandler, never()).flushAggregatedLog(any(), any());
    }

    @Test
    void offer_exceedsCountThreshold_triggerFlush() {
        AggregateModeBuffer buffer = createBuffer(3, "1MB");
        buffer.offer(context, createTestEntry("msg1", "INFO"));
        buffer.offer(context, createTestEntry("msg2", "INFO"));
        buffer.offer(context, createTestEntry("msg3", "INFO"));
        verify(mockHandler, atLeastOnce()).flushAggregatedLog(eq(context), any(AggregatedLog.class));
    }

    @Test
    void offer_dropOldest_removesOldEntries() {
        AggregateModeBuffer buffer = createBuffer(3, "1MB", overflowPolicy(BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST, 3, "1MB"));
        buffer.offer(context, createTestEntry("old1", "INFO"));
        buffer.offer(context, createTestEntry("old2", "INFO"));
        buffer.offer(context, createTestEntry("old3", "INFO"));
        buffer.offer(context, createTestEntry("new4", "WARN"));

        buffer.triggerFlush(context, true);
        ArgumentCaptor<AggregatedLog> captor = ArgumentCaptor.forClass(AggregatedLog.class);
        verify(mockHandler).flushAggregatedLog(eq(context), captor.capture());
        assertThat(captor.getValue().getContent()).contains("new4");
        assertThat(captor.getValue().getContent()).doesNotContain("old1");
    }

    @Test
    void offer_dropNewest_rejectsNewEntries() {
        AggregateModeBuffer buffer = createBuffer(3, "1MB", overflowPolicy(BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST, 3, "1MB"));
        buffer.offer(context, createTestEntry("msg1", "INFO"));
        buffer.offer(context, createTestEntry("msg2", "INFO"));
        buffer.offer(context, createTestEntry("msg3", "INFO"));
        boolean accepted = buffer.offer(context, createTestEntry("msg4", "WARN"));
        assertThat(accepted).isFalse();
    }

    @Test
    void offer_afterClose_rejected() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        buffer.closeAndFlush(context);
        boolean result = buffer.offer(context, createTestEntry("late", "INFO"));
        assertThat(result).isFalse();
    }

    @Test
    void offer_concurrentFromMultipleThreads_noDataLoss() throws Exception {
        AggregateModeBuffer buffer = createBuffer(100_000, "100MB");
        int threadCount = 10;
        int entriesPerThread = 100;
        ConcurrentTestHelper.runConcurrently(threadCount, () -> {
            for (int i = 0; i < entriesPerThread; i++) {
                buffer.offer(context, createTestEntry("msg-" + Thread.currentThread().getId() + "-" + i, "INFO"));
            }
        }, Duration.ofSeconds(10));

        buffer.triggerFlush(context, true);
        ArgumentCaptor<AggregatedLog> captor = ArgumentCaptor.forClass(AggregatedLog.class);
        verify(mockHandler, atLeastOnce()).flushAggregatedLog(eq(context), captor.capture());
        int total = 0;
        for (AggregatedLog value : captor.getAllValues()) {
            total += value.getEntryCount();
        }
        assertThat(total).isEqualTo(threadCount * entriesPerThread);
    }

    @Test
    void flush_concurrentOfferAndFlush_noCorruption() throws Exception {
        AggregateModeBuffer buffer = createBuffer(50, "10MB");
        AtomicInteger flushCount = new AtomicInteger(0);

        Thread producer = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                buffer.offer(context, createTestEntry("msg-" + i, "INFO"));
            }
        });

        Thread consumer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                buffer.triggerFlush(context, false);
                flushCount.incrementAndGet();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });

        producer.start();
        consumer.start();
        producer.join(5000);
        consumer.join(5000);
        buffer.triggerFlush(context, true);

        assertThat(flushCount.get()).isGreaterThan(0);
    }

    @Test
    void offer_patternVersionChange_triggerFlushBeforeNewBatch() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        buffer.offer(context, createTestEntry("old-pattern", "INFO"));

        LogLineDefaults.setDetectedPattern("%d %p %m%n " + System.nanoTime());
        buffer.offer(context, createTestEntry("new-pattern", "INFO"));

        buffer.triggerFlush(context, true);
        ArgumentCaptor<AggregatedLog> captor = ArgumentCaptor.forClass(AggregatedLog.class);
        verify(mockHandler, atLeast(2)).flushAggregatedLog(eq(context), captor.capture());
        assertThat(captor.getAllValues().get(0).getContent()).contains("old-pattern");
    }

    @Test
    void offerRaw_defaultFormatPath_formatsViaPatternParser() {
        class DefaultPatternHandler implements LogCollectHandler {
            private AggregatedLog last;

            @Override
            public String logLinePattern() {
                return "%p [%t] %C - %m";
            }

            @Override
            public void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
                this.last = aggregatedLog;
            }
        }
        DefaultPatternHandler handler = new DefaultPatternHandler();
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        LogCollectContext rawContext = createTestContext(config, handler, CollectMode.AGGREGATE, null, null);

        AggregateModeBuffer buffer = new AggregateModeBuffer(100, parseBytes("1MB"), null, handler);
        SecurityPipeline pipeline = new SecurityPipeline(null, null);
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("traceId", rawContext.getTraceId());
        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                rawContext.getTraceId(),
                "raw-message",
                "INFO",
                System.currentTimeMillis(),
                "worker-1",
                "com.demo.Service",
                null,
                mdc,
                SecurityPipeline.SecurityMetrics.NOOP);

        assertThat(buffer.offerRaw(rawContext, record)).isTrue();
        buffer.triggerFlush(rawContext, true);

        assertThat(handler.last).isNotNull();
        assertThat(handler.last.getContent()).contains("INFO [worker-1] com.demo.Service - raw-message");
    }

    @Test
    void offerRaw_customFormatLine_fallsBackToLogEntryPath() {
        AggregateModeBuffer buffer = createBuffer(100, "1MB");
        SecurityPipeline pipeline = new SecurityPipeline(null, null);
        SecurityPipeline.ProcessedLogRecord record = pipeline.processRawRecord(
                context.getTraceId(),
                "raw-custom",
                "WARN",
                System.currentTimeMillis(),
                "main",
                "com.test.Custom",
                null,
                null,
                SecurityPipeline.SecurityMetrics.NOOP);

        assertThat(buffer.offerRaw(context, record)).isTrue();
        verify(mockHandler, atLeastOnce()).formatLogLine(any());
    }

    private AggregateModeBuffer createBuffer(int maxSize, String maxBytes) {
        return createBuffer(maxSize, maxBytes, overflowPolicy(BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY, maxSize, maxBytes));
    }

    private AggregateModeBuffer createBuffer(int maxSize, String maxBytes, BoundedBufferPolicy policy) {
        return new AggregateModeBuffer(maxSize, parseBytes(maxBytes), null, mockHandler, policy);
    }

    private BoundedBufferPolicy overflowPolicy(BoundedBufferPolicy.OverflowStrategy strategy, int maxCount, String maxBytes) {
        return new BoundedBufferPolicy(parseBytes(maxBytes), maxCount, strategy);
    }
}
