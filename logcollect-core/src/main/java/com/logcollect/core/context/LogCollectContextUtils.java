package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * LogCollect 上下文传播工具类。
 *
 * <p>用于包装 Runnable/Callable/Executor/ThreadFactory 等异步入口，
 * 在子线程中恢复父线程快照，并在 finally 中清理上下文，避免 ThreadLocal 泄漏。
 */
public final class LogCollectContextUtils {
    private LogCollectContextUtils() {}

    /**
     * 包装 Runnable，使其在执行时自动恢复父线程上下文。
     *
     * @param runnable 原始任务
     * @return 包装后的任务；若无活跃上下文则返回原任务；入参为 null 返回 null
     */
    public static Runnable wrapRunnable(Runnable runnable) {
        if (runnable == null) {
            return null;
        }
        if (runnable instanceof LogCollectRunnableWrapper) {
            return runnable;
        }
        final List<WeakReference<LogCollectContext>> weakSnapshot = captureWeakSnapshot();
        if (weakSnapshot.isEmpty()) {
            return runnable;
        }
        return () -> {
            if (!restoreWeakSnapshot(weakSnapshot)) {
                runnable.run();
                return;
            }
            try {
                runnable.run();
            } finally {
                LogCollectContextManager.clear();
            }
        };
    }

    /**
     * 包装 Callable，使其在执行时自动恢复父线程上下文。
     *
     * @param callable 原始任务
     * @param <V>      返回值类型
     * @return 包装后的任务；若无活跃上下文则返回原任务；入参为 null 返回 null
     */
    public static <V> Callable<V> wrapCallable(Callable<V> callable) {
        if (callable == null) {
            return null;
        }
        if (callable instanceof LogCollectCallableWrapper) {
            return callable;
        }
        final List<WeakReference<LogCollectContext>> weakSnapshot = captureWeakSnapshot();
        if (weakSnapshot.isEmpty()) {
            return callable;
        }
        return () -> {
            if (!restoreWeakSnapshot(weakSnapshot)) {
                return callable.call();
            }
            try {
                return callable.call();
            } finally {
                LogCollectContextManager.clear();
            }
        };
    }

    /**
     * 包装 Consumer，使其在执行时自动恢复父线程上下文。
     *
     * @param consumer 原始消费函数
     * @param <T>      消费入参类型
     * @return 包装后的 Consumer；若无活跃上下文则返回原 Consumer；入参为 null 返回 null
     */
    public static <T> Consumer<T> wrapConsumer(Consumer<T> consumer) {
        if (consumer == null) {
            return null;
        }
        final List<WeakReference<LogCollectContext>> weakSnapshot = captureWeakSnapshot();
        if (weakSnapshot.isEmpty()) {
            return consumer;
        }
        return new Consumer<T>() {
            @Override
            public void accept(T t) {
                if (!restoreWeakSnapshot(weakSnapshot)) {
                    consumer.accept(t);
                    return;
                }
                try {
                    consumer.accept(t);
                } finally {
                    LogCollectContextManager.clear();
                }
            }
        };
    }

    /**
     * 包装 ExecutorService，使其提交的所有任务自动带上上下文传播。
     *
     * @param executor 原始线程池
     * @return 包装后的线程池；若已包装或入参为 null 则原样返回
     */
    public static ExecutorService wrapExecutorService(ExecutorService executor) {
        if (executor == null) {
            return null;
        }
        if (executor instanceof LogCollectWrappedExecutor) {
            return executor;
        }
        if (executor instanceof ScheduledExecutorService) {
            return wrapScheduledExecutorService((ScheduledExecutorService) executor);
        }
        return new LogCollectExecutorServiceWrapper(executor);
    }

    /**
     * 包装 ScheduledExecutorService，使其提交的延迟/周期任务自动带上上下文传播。
     *
     * @param executor 原始调度线程池
     * @return 包装后的调度线程池；若已包装或入参为 null 则原样返回
     */
    public static ScheduledExecutorService wrapScheduledExecutorService(ScheduledExecutorService executor) {
        if (executor == null) {
            return null;
        }
        if (executor instanceof LogCollectWrappedExecutor) {
            return executor;
        }
        return new LogCollectScheduledExecutorServiceWrapper(executor);
    }

    /**
     * 包装通用 Executor。
     *
     * @param executor 原始执行器
     * @return 包装后的执行器；入参为 null 返回 null
     */
    public static Executor wrapExecutor(final Executor executor) {
        if (executor == null) {
            return null;
        }
        if (executor instanceof LogCollectWrappedExecutor) {
            return executor;
        }
        return new LogCollectExecutorWrapper(executor);
    }

    /**
     * 创建带上下文传播的普通线程。
     *
     * @param runnable 线程任务
     * @param name     线程名
     * @return 新建线程（未启动）
     */
    public static Thread newThread(Runnable runnable, String name) {
        Thread t = new Thread(wrapRunnable(runnable), name);
        return t;
    }

    /**
     * 创建带上下文传播的守护线程。
     *
     * @param runnable 线程任务
     * @param name     线程名
     * @return 新建守护线程（未启动）
     */
    public static Thread newDaemonThread(Runnable runnable, String name) {
        Thread t = newThread(runnable, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 创建默认线程工厂，自动包装任务并按前缀命名线程。
     *
     * @param namePrefix 线程名前缀
     * @return 线程工厂
     */
    public static ThreadFactory threadFactory(String namePrefix) {
        return new LogCollectThreadFactory(namePrefix);
    }

    /**
     * 包装已有线程工厂，使其创建出的线程自动传播上下文。
     *
     * @param factory 原始工厂
     * @return 包装后的工厂；若已包装或入参为 null 则原样返回
     */
    public static ThreadFactory wrapThreadFactory(ThreadFactory factory) {
        if (factory == null) {
            return null;
        }
        if (factory instanceof LogCollectThreadFactoryWrapper) {
            return factory;
        }
        return new LogCollectThreadFactoryWrapper(factory);
    }

    /**
     * CompletableFuture 便捷方法：自动包装 Supplier。
     *
     * @param supplier 任务提供者
     * @param <U>      返回值类型
     * @return CompletableFuture
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return CompletableFuture.supplyAsync(wrapSupplier(supplier));
    }

    /**
     * CompletableFuture 便捷方法：自动包装 Runnable。
     *
     * @param runnable 任务
     * @return CompletableFuture
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(wrapRunnable(runnable));
    }

    /**
     * 判断当前线程是否存在活跃 LogCollect 上下文。
     *
     * @return true 表示在收集范围内
     */
    public static boolean isInLogCollectContext() {
        return LogCollectContextManager.current() != null;
    }

    /**
     * 返回简要诊断信息。
     *
     * @return 诊断字符串（当前包含上下文栈深度）
     */
    public static String diagnosticInfo() {
        return "depth=" + LogCollectContextManager.depth();
    }

    /**
     * 包装 Supplier（包级可见，供工具类内部复用）。
     *
     * @param supplier 原始供应函数
     * @param <U>      返回值类型
     * @return 包装后的 Supplier；若无活跃上下文则返回原 Supplier；入参为 null 返回 null
     */
    static <U> Supplier<U> wrapSupplier(final Supplier<U> supplier) {
        if (supplier == null) {
            return null;
        }
        final List<WeakReference<LogCollectContext>> weakSnapshot = captureWeakSnapshot();
        if (weakSnapshot.isEmpty()) {
            return supplier;
        }
        return new Supplier<U>() {
            @Override
            public U get() {
                if (!restoreWeakSnapshot(weakSnapshot)) {
                    return supplier.get();
                }
                try {
                    return supplier.get();
                } finally {
                    LogCollectContextManager.clear();
                }
            }
        };
    }

    /**
     * 批量包装 Callable 集合（包级可见，供 ExecutorService 包装器调用）。
     *
     * @param tasks 原始任务集合
     * @param <T>   返回值类型
     * @return 包装后的任务列表；当入参为 null 时返回空列表
     */
    static <T> List<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<Callable<T>>();
        if (tasks == null) {
            return wrapped;
        }
        for (Callable<T> task : tasks) {
            wrapped.add(wrapCallable(task));
        }
        return wrapped;
    }

    private static List<WeakReference<LogCollectContext>> captureWeakSnapshot() {
        Deque<LogCollectContext> snapshot = LogCollectContextManager.snapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<WeakReference<LogCollectContext>> weakSnapshot =
                new ArrayList<WeakReference<LogCollectContext>>(snapshot.size());
        for (LogCollectContext context : snapshot) {
            weakSnapshot.add(new WeakReference<LogCollectContext>(context));
        }
        return weakSnapshot;
    }

    private static boolean restoreWeakSnapshot(List<WeakReference<LogCollectContext>> weakSnapshot) {
        if (weakSnapshot == null || weakSnapshot.isEmpty()) {
            LogCollectContextManager.clear();
            return false;
        }
        Deque<LogCollectContext> restored = new java.util.ArrayDeque<LogCollectContext>(weakSnapshot.size());
        for (WeakReference<LogCollectContext> reference : weakSnapshot) {
            LogCollectContext context = reference == null ? null : reference.get();
            if (context == null) {
                LogCollectContextManager.clear();
                return false;
            }
            restored.addLast(context);
        }
        if (restored.isEmpty()) {
            LogCollectContextManager.clear();
            return false;
        }
        LogCollectContextManager.restore(restored);
        return true;
    }
}
