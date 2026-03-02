package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogCollectContextManagerAdditionalTest extends CoreUnitTestBase {

    @AfterEach
    void clearThreadLocalAfterEach() {
        LogCollectContextManager.clear();
    }

    @Test
    void push_tripleNested_stackOrderCorrect() {
        LogCollectContext c1 = createTestContext();
        LogCollectContext c2 = createTestContext();
        LogCollectContext c3 = createTestContext();

        LogCollectContextManager.push(c1);
        LogCollectContextManager.push(c2);
        LogCollectContextManager.push(c3);

        assertThat(LogCollectContextManager.current()).isSameAs(c3);
        assertThat(LogCollectContextManager.depth()).isEqualTo(3);

        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isSameAs(c2);
        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isSameAs(c1);
        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void pop_emptyStack_safeAndReturnsNull() {
        assertDoesNotThrow(() -> {
            LogCollectContext popped = LogCollectContextManager.pop();
            assertThat(popped).isNull();
        });
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void push_nullContext_ignored() {
        LogCollectContextManager.push(null);
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void clear_multipleCalls_idempotent() {
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.clear();
        LogCollectContextManager.clear();
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void restore_nullSnapshot_clearsContext() {
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.restore(null);
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void snapshotAndRestore_roundTrip() {
        LogCollectContext c1 = createTestContext();
        LogCollectContext c2 = createTestContext();
        LogCollectContextManager.push(c1);
        LogCollectContextManager.push(c2);

        Deque<LogCollectContext> snapshot = LogCollectContextManager.snapshot();
        LogCollectContextManager.clear();
        assertThat(LogCollectContextManager.current()).isNull();

        LogCollectContextManager.restore(snapshot);
        assertThat(LogCollectContextManager.current()).isSameAs(c2);
        assertThat(LogCollectContextManager.depth()).isEqualTo(2);
    }

    @Test
    void restoreSnapshot_empty_clearsContext() {
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.restoreSnapshot(LogCollectContextSnapshot.EMPTY);
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void captureAndRestoreSnapshot_acrossThread() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);
        LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
        LogCollectContextManager.clear();

        AtomicReference<String> traceId = new AtomicReference<String>();
        AtomicReference<Boolean> leaked = new AtomicReference<Boolean>(Boolean.TRUE);

        Thread thread = new Thread(() -> {
            LogCollectContextManager.restoreSnapshot(snapshot);
            LogCollectContext current = LogCollectContextManager.current();
            traceId.set(current == null ? null : current.getTraceId());
            LogCollectContextManager.clearSnapshotContext();
            leaked.set(LogCollectContextManager.current() != null);
        });
        thread.start();
        thread.join(3000);

        assertThat(traceId.get()).isEqualTo(ctx.getTraceId());
        assertThat(leaked.get()).isFalse();
    }
}
