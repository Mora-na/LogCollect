package com.logcollect.samples.shared;

import com.logcollect.api.model.LogCollectContext;
import org.slf4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SampleScenarioSupport {
    public static final long TIMEOUT_SECONDS = 10L;

    private SampleScenarioSupport() {
    }

    public static void enterScenario(Logger logger, String code, String title, int expectedCollectedCount) {
        String label = scenarioLabel(code, title);
        LogCollectContext.setCurrentBusinessId(label);
        LogCollectContext.setCurrentAttribute("sampleScenarioCode", code);
        LogCollectContext.setCurrentAttribute("sampleScenarioTitle", title);
        LogCollectContext.setCurrentAttribute("sampleExpectedCollectedCount", Integer.valueOf(expectedCollectedCount));
        logger.info("[{}][步骤00] 进入场景，traceId={}", label, LogCollectContext.getCurrentTraceId());
    }

    public static void step(Logger logger, String code, String title, String step, String message, Object... args) {
        logger.info(prefix(code, title, step) + message, args);
    }

    public static void await(CountDownLatch latch, String label) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(label + " 在 " + TIMEOUT_SECONDS + " 秒内未完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " 等待期间被中断", e);
        }
    }

    public static <T> T get(Future<T> future, String label) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(label + " 执行失败", e);
        }
    }

    public static void join(Thread thread, String label) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            if (thread.isAlive()) {
                throw new IllegalStateException(label + " 在线程 join 后仍未结束");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " join 期间被中断", e);
        }
    }

    public static String scenarioLabel(String code, String title) {
        return "场景" + code + "-" + title;
    }

    private static String prefix(String code, String title, String step) {
        return "[" + scenarioLabel(code, title) + "][步骤" + step + "] ";
    }
}
