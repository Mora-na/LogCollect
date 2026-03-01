package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ThreadContextElement;

import java.util.Deque;

/**
 * Kotlin Coroutines 上下文传播元素。
 * 使用方式：withContext(new LogCollectCoroutineContext()) { ... }
 */
public class LogCollectCoroutineContext extends AbstractCoroutineContextElement
        implements ThreadContextElement<Deque<LogCollectContext>> {

    public static final Key KEY = new Key();

    private final Deque<LogCollectContext> snapshot;

    public LogCollectCoroutineContext() {
        super(KEY);
        this.snapshot = LogCollectContextManager.snapshot();
    }

    @Override
    public Deque<LogCollectContext> updateThreadContext(CoroutineContext context) {
        Deque<LogCollectContext> previous = LogCollectContextManager.snapshot();
        LogCollectContextManager.restore(snapshot);
        return previous;
    }

    @Override
    public void restoreThreadContext(CoroutineContext context, Deque<LogCollectContext> oldState) {
        if (oldState == null || oldState.isEmpty()) {
            LogCollectContextManager.clear();
        } else {
            LogCollectContextManager.restore(oldState);
        }
    }

    public static final class Key implements CoroutineContext.Key<LogCollectCoroutineContext> {
    }
}
