package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectContextManagerTest extends CoreUnitTestBase {

    @Test
    void pushAndPop_singleContext_worksCorrectly() {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);
        assertThat(LogCollectContextManager.current()).isSameAs(ctx);
        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isNull();
    }

    @Test
    void push_nested_stackOrder() {
        LogCollectContext outer = createTestContext();
        LogCollectContext inner = createTestContext();

        LogCollectContextManager.push(outer);
        assertThat(LogCollectContextManager.current()).isSameAs(outer);

        LogCollectContextManager.push(inner);
        assertThat(LogCollectContextManager.current()).isSameAs(inner);

        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isSameAs(outer);

        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isNull();
    }

    @Test
    void push_differentThreads_isolated() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        AtomicReference<LogCollectContext> otherThreadCtx = new AtomicReference<LogCollectContext>();
        Thread t = new Thread(() -> otherThreadCtx.set(LogCollectContextManager.current()));
        t.start();
        t.join();

        assertThat(otherThreadCtx.get()).isNull();
        LogCollectContextManager.pop();
    }

    @Test
    void pop_lastContext_removesThreadLocal() {
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.pop();
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void clear_clearsAll() {
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.clear();
        assertThat(LogCollectContextManager.current()).isNull();
        assertThat(LogCollectContextManager.depth()).isZero();
    }

    @Test
    void depth_reflectsStackSize() {
        LogCollectContextManager.push(createTestContext());
        LogCollectContextManager.push(createTestContext());
        assertThat(LogCollectContextManager.depth()).isEqualTo(2);
        LogCollectContextManager.clear();
    }
}
