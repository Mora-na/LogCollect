package com.logcollect.core.security;

import java.util.concurrent.TimeUnit;

/**
 * 在 charAt() 中进行超时检查的 CharSequence 包装器。
 */
final class TimeBoundedCharSequence implements CharSequence {

    private static final int CHECK_INTERVAL = 64;
    private static final int CHECK_MASK = CHECK_INTERVAL - 1;

    private final CharSequence delegate;
    private final long deadlineNanos;
    private int counter;

    TimeBoundedCharSequence(CharSequence delegate, long timeoutMs) {
        this(delegate, timeoutMs, false);
    }

    private TimeBoundedCharSequence(CharSequence delegate, long value, boolean absoluteDeadline) {
        this.delegate = delegate;
        this.deadlineNanos = absoluteDeadline
                ? value
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, value));
        this.counter = 0;
    }

    TimeBoundedCharSequence wrap(CharSequence nextDelegate) {
        return new TimeBoundedCharSequence(nextDelegate, deadlineNanos, true);
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public char charAt(int index) {
        if ((++counter & CHECK_MASK) == 0 && System.nanoTime() > deadlineNanos) {
            throw new RegexTimeoutException("mask regex timeout after " + counter + " charAt calls");
        }
        return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new TimeBoundedCharSequence(delegate.subSequence(start, end), deadlineNanos, true);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
