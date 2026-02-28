package com.logcollect.test;

import com.logcollect.core.context.LogCollectContextManager;

public final class LogCollectTestUtils {
    private LogCollectTestUtils() {}

    public static void clearContext() {
        LogCollectContextManager.clearSnapshotContext();
    }
}
