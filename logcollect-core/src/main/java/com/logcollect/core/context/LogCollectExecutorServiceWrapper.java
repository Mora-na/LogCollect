package com.logcollect.core.context;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

/**
 * ExecutorService 包装器。
 *
 * <p>对提交入口统一做上下文包装，确保线程池任务能继承父线程 LogCollect 上下文。
 * 线程池生命周期控制（shutdown/awaitTermination）完全委托给原始线程池。
 */
public class LogCollectExecutorServiceWrapper implements ExecutorService {
    /** 原始线程池。 */
    private final ExecutorService delegate;

    /**
     * @param delegate 原始线程池
     */
    public LogCollectExecutorServiceWrapper(ExecutorService delegate) {
        this.delegate = delegate;
    }

    /** 提交 Runnable 前自动包装上下文。 */
    @Override
    public void execute(Runnable command) {
        delegate.execute(LogCollectContextUtils.wrapRunnable(command));
    }

    /** 提交 Callable 前自动包装上下文。 */
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(LogCollectContextUtils.wrapCallable(task));
    }

    /** 提交 Runnable 前自动包装上下文。 */
    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(LogCollectContextUtils.wrapRunnable(task));
    }

    /** 提交 Runnable 前自动包装上下文。 */
    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(LogCollectContextUtils.wrapRunnable(task), result);
    }

    /** 批量任务执行前逐个包装上下文。 */
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(LogCollectContextUtils.wrapCallables(tasks));
    }

    /** 批量任务执行前逐个包装上下文。 */
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(LogCollectContextUtils.wrapCallables(tasks), timeout, unit);
    }

    /** 批量任务执行前逐个包装上下文。 */
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(LogCollectContextUtils.wrapCallables(tasks));
    }

    /** 批量任务执行前逐个包装上下文。 */
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(LogCollectContextUtils.wrapCallables(tasks), timeout, unit);
    }

    /** 委托原始线程池关闭。 */
    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    /** 委托原始线程池立即关闭。 */
    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    /** @return 原始线程池是否已关闭。 */
    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    /** @return 原始线程池是否已终止。 */
    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    /** 委托原始线程池等待终止。 */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
