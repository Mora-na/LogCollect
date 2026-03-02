package com.logcollect.core.test;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.util.DataSizeParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core 模块纯单元测试基类，不依赖 Spring 容器。
 */
public abstract class CoreUnitTestBase {

    @BeforeEach
    void cleanThreadLocalBeforeEach() {
        LogCollectContextManager.clear();
    }

    @AfterEach
    void verifyNoContextLeak() {
        assertThat(LogCollectContextManager.current())
                .as("测试结束后 ThreadLocal 应已清理")
                .isNull();
        LogCollectContextManager.clear();
    }

    protected LogCollectContext createTestContext() {
        return createTestContext(LogCollectConfig.frameworkDefaults(), new LogCollectHandler() {
        }, CollectMode.AGGREGATE, null, null);
    }

    protected LogCollectContext createTestContext(LogCollectConfig config,
                                                  LogCollectHandler handler,
                                                  CollectMode mode,
                                                  Object buffer,
                                                  Object breaker) {
        try {
            Method marker = CoreUnitTestBase.class.getDeclaredMethod("marker");
            return new LogCollectContext(
                    UUID.randomUUID().toString(),
                    marker,
                    new Object[0],
                    config == null ? LogCollectConfig.frameworkDefaults() : config,
                    handler == null ? new LogCollectHandler() {
                    } : handler,
                    buffer,
                    breaker,
                    mode == null ? CollectMode.AGGREGATE : mode
            );
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void marker() {
    }

    protected LogEntry createTestEntry(String content, String level) {
        return LogEntry.builder()
                .traceId("test-trace-id")
                .content(content)
                .level(level)
                .timestamp(System.currentTimeMillis())
                .threadName("test-thread")
                .loggerName("com.test.TestClass")
                .build();
    }

    protected LogEntry createTestEntryWithThrowable(String content, String throwableStr) {
        return LogEntry.builder()
                .traceId("test-trace-id")
                .content(content)
                .level("ERROR")
                .timestamp(System.currentTimeMillis())
                .threadName("test-thread")
                .loggerName("com.test.TestClass")
                .throwableString(throwableStr)
                .build();
    }

    protected long parseBytes(String value) {
        return DataSizeParser.parseToBytes(value);
    }
}
