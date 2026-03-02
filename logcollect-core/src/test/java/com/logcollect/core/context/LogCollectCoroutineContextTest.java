package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.test.CoreUnitTestBase;
import kotlin.coroutines.EmptyCoroutineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectCoroutineContextTest extends CoreUnitTestBase {

    @AfterEach
    void clean() {
        LogCollectContextManager.clear();
    }

    @Test
    void updateAndRestore_withSnapshot_roundTrip() {
        LogCollectContext c1 = createTestContext();
        LogCollectContext c2 = createTestContext();
        LogCollectContextManager.push(c1);

        LogCollectCoroutineContext coroutineContext = new LogCollectCoroutineContext();
        LogCollectContextManager.push(c2);

        Deque<LogCollectContext> previous = coroutineContext.updateThreadContext(EmptyCoroutineContext.INSTANCE);
        assertThat(LogCollectContextManager.current()).isSameAs(c1);
        assertThat(previous).isNotNull();

        coroutineContext.restoreThreadContext(EmptyCoroutineContext.INSTANCE, previous);
        assertThat(LogCollectContextManager.current()).isSameAs(c2);
    }

    @Test
    void restore_withNullOrEmptyState_clearsContext() {
        LogCollectContextManager.push(createTestContext());
        LogCollectCoroutineContext coroutineContext = new LogCollectCoroutineContext();

        coroutineContext.restoreThreadContext(EmptyCoroutineContext.INSTANCE, null);
        assertThat(LogCollectContextManager.current()).isNull();

        LogCollectContextManager.push(createTestContext());
        coroutineContext.restoreThreadContext(EmptyCoroutineContext.INSTANCE, new ArrayDeque<LogCollectContext>());
        assertThat(LogCollectContextManager.current()).isNull();
    }

    @Test
    void key_exposed() {
        assertThat(LogCollectCoroutineContext.KEY).isNotNull();
    }
}
