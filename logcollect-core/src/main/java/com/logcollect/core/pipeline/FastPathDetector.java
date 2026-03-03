package com.logcollect.core.pipeline;

/**
 * 安全流水线前置快速探测器。
 *
 * <p>通过单次 O(n) 扫描返回位掩码，指导后续是否需要执行 sanitize/mask。
 */
public final class FastPathDetector {

    public static final int FLAG_NEEDS_SANITIZE = 1;
    public static final int FLAG_NEEDS_MASK = 1 << 1;

    private FastPathDetector() {
    }

    /**
     * 兼容默认消息扫描（严格模式）。
     */
    public static int scan(String input) {
        return scanMessage(input);
    }

    /**
     * 消息字段扫描（严格控制字符策略）。
     */
    public static int scanMessage(String input) {
        return scanInternal(input, true);
    }

    /**
     * Throwable 字段扫描。
     *
     * <p>Throwable 即使是干净堆栈也需要按行校验，因此包含换行时应执行 sanitizeThrowable。
     */
    public static int scanThrowable(String input) {
        return scanInternal(input, false);
    }

    private static int scanInternal(String input, boolean strictMessageMode) {
        if (input == null || input.isEmpty()) {
            return 0;
        }

        int flags = 0;
        int consecutiveDigits = 0;
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);

            if (needsSanitize(c, strictMessageMode)) {
                flags |= FLAG_NEEDS_SANITIZE;
            }

            if (c >= '0' && c <= '9') {
                consecutiveDigits++;
                if (consecutiveDigits >= 11) {
                    flags |= FLAG_NEEDS_MASK;
                }
            } else {
                consecutiveDigits = 0;
                if (c == '@') {
                    flags |= FLAG_NEEDS_MASK;
                }
            }

            if (flags == (FLAG_NEEDS_SANITIZE | FLAG_NEEDS_MASK)) {
                return flags;
            }
        }
        return flags;
    }

    private static boolean needsSanitize(char c, boolean strictMessageMode) {
        if (c == '<' || c == '\u001B') {
            return true;
        }
        if (strictMessageMode) {
            return c < 0x20 && c != ' ';
        }
        if (c == '\n') {
            return true;
        }
        if (c == '\r' || c == '\t') {
            return false;
        }
        return c < 0x20 || c == 0x7F;
    }
}
