package com.logcollect.autoconfigure.async;

import com.logcollect.core.context.LogCollectContextUtils;
import org.springframework.core.task.TaskDecorator;

/**
 * Spring 异步任务装饰器。
 *
 * <p>用于给 {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}
 * 和 {@link org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler}
 * 的任务统一注入 LogCollect 上下文传播能力。
 */
public class LogCollectTaskDecorator implements TaskDecorator {
    /**
     * 包装异步任务，在子线程中恢复父线程上下文。
     *
     * @param runnable 原始异步任务
     * @return 包装后的任务
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        return LogCollectContextUtils.wrapRunnable(runnable);
    }
}
