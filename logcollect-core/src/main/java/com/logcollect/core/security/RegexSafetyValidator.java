package com.logcollect.core.security;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RegexSafetyValidator {
    private static final int MAX_REGEX_LENGTH = 500;
    private static final long REGEX_TEST_TIMEOUT_MS = 100;
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*][^)]*\\)\\s*[+*]");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "logcollect-regex-validator");
            t.setDaemon(true);
            return t;
        }
    });

    private RegexSafetyValidator() {}

    public static boolean isSafe(String regex) {
        if (regex == null || regex.isEmpty()) {
            return false;
        }
        if (regex.length() > MAX_REGEX_LENGTH) {
            return false;
        }
        if (NESTED_QUANTIFIER.matcher(regex).find()) {
            return false;
        }
        try {
            final Pattern pattern = Pattern.compile(regex);
            Future<Boolean> future = EXECUTOR.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    String evil = "aaaaaaaaaaaaaaaaaaaa!";
                    return pattern.matcher(evil).matches();
                }
            });
            try {
                future.get(REGEX_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                return false;
            }
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Regex validation error", e);
            return false;
        } catch (Error e) {
            throw e;
        }
    }

    public static Pattern safeCompile(String regex) {
        if (!isSafe(regex)) {
            LogCollectInternalLogger.warn("Rejected unsafe regex: {}", regex);
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            LogCollectInternalLogger.warn("Invalid regex: {}", regex, e);
            return null;
        }
    }
}
