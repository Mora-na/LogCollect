package com.logcollect.core.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * JDK 8/9+ compatible spin wait hint.
 */
public final class SpinWaitHint {

    private static final MethodHandle ON_SPIN_WAIT;

    static {
        MethodHandle handle = null;
        try {
            handle = MethodHandles.lookup()
                    .findStatic(Thread.class, "onSpinWait", MethodType.methodType(void.class));
        } catch (NoSuchMethodException ignored) {
            // JDK 8 fallback
        } catch (IllegalAccessException ignored) {
            // fallback to no-op
        }
        ON_SPIN_WAIT = handle;
    }

    private SpinWaitHint() {
    }

    public static void onSpinWait() {
        if (ON_SPIN_WAIT == null) {
            return;
        }
        try {
            ON_SPIN_WAIT.invokeExact();
        } catch (Throwable ignored) {
            // no-op fallback
        }
    }
}
