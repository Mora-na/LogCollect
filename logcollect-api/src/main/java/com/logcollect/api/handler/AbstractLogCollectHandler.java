package com.logcollect.api.handler;

/**
 * LogCollect Handler 抽象基类。
 *
 * <p>提供中断检查模板，便于在 {@code handlerTimeoutMs} 超时后快速响应线程中断。
 */
public abstract class AbstractLogCollectHandler implements LogCollectHandler {

    /**
     * 检查当前线程是否已被中断。
     *
     * @throws InterruptedException 线程被中断时抛出
     */
    protected final void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Handler execution interrupted by timeout");
        }
    }
}
