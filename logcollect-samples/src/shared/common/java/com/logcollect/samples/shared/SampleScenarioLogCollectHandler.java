package com.logcollect.samples.shared;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectContext;
import org.springframework.stereotype.Component;

@Component
public class SampleScenarioLogCollectHandler implements LogCollectHandler {
    private static final Object LOCK = new Object();

    @Override
    public CollectMode preferredMode() {
        return CollectMode.AGGREGATE;
    }

    @Override
    public void before(LogCollectContext context) {
        if (context.getBusinessId() == null) {
            context.setBusinessId(context.getMethodName());
        }
    }

    @Override
    public void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        synchronized (LOCK) {
            String scenario = context.getBusinessId() == null
                    ? context.getMethodName()
                    : String.valueOf(context.getBusinessId());
            System.out.println("========== LogCollect 收集结果开始 ==========");
            System.out.println("场景: " + scenario);
            System.out.println("traceId: " + context.getTraceId());
            System.out.println("method: " + context.getMethodSignature());
            System.out.println("entryCount: " + aggregatedLog.getEntryCount()
                    + ", finalFlush=" + aggregatedLog.isFinalFlush());
            System.out.println(aggregatedLog.getContent());
            System.out.println("========== LogCollect 收集结果结束 ==========");
        }
    }

    @Override
    public void after(LogCollectContext context) {
        synchronized (LOCK) {
            String scenario = context.getBusinessId() == null
                    ? context.getMethodName()
                    : String.valueOf(context.getBusinessId());
            Integer expectedCollected = context.getAttribute("sampleExpectedCollectedCount", Integer.class);
            int actualCollected = context.getTotalCollectedCount();
            int discarded = context.getTotalDiscardedCount();
            int flushCount = context.getFlushCount();
            boolean passed = expectedCollected != null
                    && expectedCollected.intValue() == actualCollected
                    && discarded == 0
                    && flushCount == 1;
            System.out.println("场景收尾: " + scenario
                    + ", collected=" + actualCollected
                    + ", discarded=" + discarded
                    + ", flushCount=" + flushCount);
            System.out.println("场景校验: " + scenario
                    + ", expected=" + (expectedCollected == null ? "N/A" : expectedCollected)
                    + ", collected=" + actualCollected
                    + ", discarded=" + discarded
                    + ", flushCount=" + flushCount
                    + ", status=" + (passed ? "PASS" : "FAIL"));
        }
    }
}
