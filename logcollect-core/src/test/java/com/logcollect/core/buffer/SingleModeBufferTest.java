package com.logcollect.core.buffer;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.test.ConcurrentTestHelper;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SingleModeBufferTest extends CoreUnitTestBase {

    private LogCollectContext context;
    private LogCollectHandler handler;

    @BeforeEach
    void setUp() {
        handler = mock(LogCollectHandler.class);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setAsync(false);
        context = createTestContext(config, handler, CollectMode.SINGLE, null, null);
    }

    @Test
    void flush_drainedEntries_loopCallAppendLog() {
        SingleModeBuffer buffer = new SingleModeBuffer(100, parseBytes("1MB"), null,
                overflowPolicy(BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY, 100, "1MB"));

        buffer.offer(context, createTestEntry("msg1", "INFO"));
        buffer.offer(context, createTestEntry("msg2", "WARN"));

        buffer.triggerFlush(context, true);
        verify(handler, times(2)).appendLog(eq(context), any(LogEntry.class));
    }

    @Test
    void offer_concurrentSafety_allEntriesPreserved() throws Exception {
        SingleModeBuffer buffer = new SingleModeBuffer(100_000, parseBytes("100MB"), null,
                overflowPolicy(BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY, 100_000, "100MB"));
        int threadCount = 8;
        int entriesPerThread = 200;

        ConcurrentTestHelper.runConcurrently(threadCount, () -> {
            for (int i = 0; i < entriesPerThread; i++) {
                buffer.offer(context, createTestEntry("msg", "INFO"));
            }
        }, Duration.ofSeconds(10));

        buffer.triggerFlush(context, true);
        verify(handler, times(threadCount * entriesPerThread)).appendLog(eq(context), any(LogEntry.class));
    }

    @Test
    void offer_exceedsCountThreshold_triggerFlush() {
        SingleModeBuffer buffer = new SingleModeBuffer(3, parseBytes("1MB"), null,
                overflowPolicy(BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY, 3, "1MB"));
        buffer.offer(context, createTestEntry("msg1", "INFO"));
        buffer.offer(context, createTestEntry("msg2", "INFO"));
        buffer.offer(context, createTestEntry("msg3", "INFO"));
        verify(handler, atLeast(3)).appendLog(eq(context), any(LogEntry.class));
    }

    @Test
    void offer_dropOldest_removesOldEntries() {
        SingleModeBuffer buffer = new SingleModeBuffer(3, parseBytes("1MB"), null,
                overflowPolicy(BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST, 3, "1MB"));
        buffer.offer(context, createTestEntry("old1", "INFO"));
        buffer.offer(context, createTestEntry("old2", "INFO"));
        buffer.offer(context, createTestEntry("old3", "INFO"));
        buffer.offer(context, createTestEntry("new4", "WARN"));
        buffer.triggerFlush(context, true);

        verify(handler, never()).appendLog(eq(context), argThat(entry -> "old1".equals(entry.getContent())));
        verify(handler, atLeastOnce()).appendLog(eq(context), argThat(entry -> "new4".equals(entry.getContent())));
    }

    @Test
    void offer_dropNewest_rejectsNewEntries() {
        SingleModeBuffer buffer = new SingleModeBuffer(3, parseBytes("1MB"), null,
                overflowPolicy(BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST, 3, "1MB"));
        buffer.offer(context, createTestEntry("msg1", "INFO"));
        buffer.offer(context, createTestEntry("msg2", "INFO"));
        buffer.offer(context, createTestEntry("msg3", "INFO"));
        boolean accepted = buffer.offer(context, createTestEntry("msg4", "WARN"));
        assertThat(accepted).isFalse();
    }

    @Test
    void offer_afterClose_rejected() {
        SingleModeBuffer buffer = new SingleModeBuffer(100, parseBytes("1MB"), null,
                overflowPolicy(BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY, 100, "1MB"));
        buffer.closeAndFlush(context);
        boolean accepted = buffer.offer(context, createTestEntry("late", "INFO"));
        assertThat(accepted).isFalse();
    }

    private BoundedBufferPolicy overflowPolicy(BoundedBufferPolicy.OverflowStrategy strategy, int maxCount, String maxBytes) {
        return new BoundedBufferPolicy(parseBytes(maxBytes), maxCount, strategy);
    }
}
