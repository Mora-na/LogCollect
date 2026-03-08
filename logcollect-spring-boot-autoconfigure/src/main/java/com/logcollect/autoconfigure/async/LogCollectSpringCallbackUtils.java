package com.logcollect.autoconfigure.async;

import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;
import org.springframework.util.concurrent.FailureCallback;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.SuccessCallback;

/**
 * Spring 回调型 API 的上下文传播工具。
 *
 * <p>用于包装 {@link ListenableFutureCallback}、{@link SuccessCallback}、
 * {@link FailureCallback} 等 Spring 专属回调，在回调线程中恢复父线程快照。
 */
public final class LogCollectSpringCallbackUtils {
    private LogCollectSpringCallbackUtils() {}

    /**
     * 包装 Spring {@link ListenableFutureCallback}。
     *
     * @param callback 原始回调
     * @param <T>      结果类型
     * @return 包装后的回调；若无活跃上下文则返回原回调；入参为 null 返回 null
     */
    public static <T> ListenableFutureCallback<T> wrapListenableFutureCallback(ListenableFutureCallback<T> callback) {
        if (callback == null) {
            return null;
        }
        LogCollectContextSnapshot snapshot = captureSnapshot();
        if (snapshot.isEmpty()) {
            return callback;
        }
        return new ListenableFutureCallback<T>() {
            @Override
            public void onFailure(Throwable ex) {
                runWithSnapshot(snapshot, new Runnable() {
                    @Override
                    public void run() {
                        callback.onFailure(ex);
                    }
                });
            }

            @Override
            public void onSuccess(T result) {
                runWithSnapshot(snapshot, new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(result);
                    }
                });
            }
        };
    }

    /**
     * 包装 Spring {@link SuccessCallback}。
     *
     * @param callback 原始成功回调
     * @param <T>      结果类型
     * @return 包装后的回调；若无活跃上下文则返回原回调；入参为 null 返回 null
     */
    public static <T> SuccessCallback<T> wrapSuccessCallback(SuccessCallback<T> callback) {
        if (callback == null) {
            return null;
        }
        LogCollectContextSnapshot snapshot = captureSnapshot();
        if (snapshot.isEmpty()) {
            return callback;
        }
        return new SuccessCallback<T>() {
            @Override
            public void onSuccess(T result) {
                runWithSnapshot(snapshot, new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess(result);
                    }
                });
            }
        };
    }

    /**
     * 包装 Spring {@link FailureCallback}。
     *
     * @param callback 原始失败回调
     * @return 包装后的回调；若无活跃上下文则返回原回调；入参为 null 返回 null
     */
    public static FailureCallback wrapFailureCallback(FailureCallback callback) {
        if (callback == null) {
            return null;
        }
        LogCollectContextSnapshot snapshot = captureSnapshot();
        if (snapshot.isEmpty()) {
            return callback;
        }
        return new FailureCallback() {
            @Override
            public void onFailure(Throwable ex) {
                runWithSnapshot(snapshot, new Runnable() {
                    @Override
                    public void run() {
                        callback.onFailure(ex);
                    }
                });
            }
        };
    }

    private static LogCollectContextSnapshot captureSnapshot() {
        return LogCollectContextManager.captureSnapshot();
    }

    private static void runWithSnapshot(LogCollectContextSnapshot snapshot, Runnable action) {
        LogCollectContextSnapshot previous = LogCollectContextManager.captureSnapshot();
        LogCollectContextManager.restoreSnapshot(snapshot);
        try {
            action.run();
        } finally {
            restorePreviousSnapshot(previous);
        }
    }

    private static void restorePreviousSnapshot(LogCollectContextSnapshot previous) {
        if (previous == null || previous.isEmpty()) {
            LogCollectContextManager.clearSnapshotContext();
            return;
        }
        LogCollectContextManager.restoreSnapshot(previous);
    }
}
