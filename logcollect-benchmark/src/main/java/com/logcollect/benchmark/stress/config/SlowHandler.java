package com.logcollect.benchmark.stress.config;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectContext;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class SlowHandler implements LogCollectHandler {

    public enum Mode {
        NORMAL,
        SLOW,
        INTERMITTENT_FAIL,
        TOTAL_FAIL
    }

    private volatile Mode mode = Mode.NORMAL;
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failCount = new AtomicLong(0);

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        switch (mode) {
            case NORMAL:
                sleep(1);
                break;
            case SLOW:
                sleep(10 + ThreadLocalRandom.current().nextInt(40));
                break;
            case INTERMITTENT_FAIL:
                if (ThreadLocalRandom.current().nextDouble() < 0.3d) {
                    failCount.incrementAndGet();
                    throw new RuntimeException("Simulated DB connection timeout");
                }
                sleep(1);
                break;
            case TOTAL_FAIL:
                failCount.incrementAndGet();
                throw new RuntimeException("Simulated DB unavailable");
            default:
                break;
        }
        successCount.incrementAndGet();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailCount() {
        return failCount.get();
    }

    public void reset() {
        successCount.set(0);
        failCount.set(0);
    }
}
