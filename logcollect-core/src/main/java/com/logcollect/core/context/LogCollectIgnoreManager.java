package com.logcollect.core.context;

/**
 * {@code @LogCollectIgnore} 线程态管理器。
 */
public final class LogCollectIgnoreManager {

    private static final ThreadLocal<Integer> IGNORE_DEPTH = new ThreadLocal<Integer>();

    private LogCollectIgnoreManager() {
    }

    public static void enter() {
        Integer depth = IGNORE_DEPTH.get();
        IGNORE_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void exit() {
        Integer depth = IGNORE_DEPTH.get();
        if (depth == null || depth <= 1) {
            IGNORE_DEPTH.remove();
            return;
        }
        IGNORE_DEPTH.set(depth - 1);
    }

    public static boolean isIgnored() {
        Integer depth = IGNORE_DEPTH.get();
        return depth != null && depth > 0;
    }

    public static void clear() {
        IGNORE_DEPTH.remove();
    }
}
