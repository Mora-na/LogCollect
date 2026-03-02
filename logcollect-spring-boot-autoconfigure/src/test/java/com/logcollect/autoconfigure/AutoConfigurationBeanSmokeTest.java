package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.async.LogCollectAsyncConfigurer;
import com.logcollect.autoconfigure.jdbc.TransactionalLogCollectHandlerWrapper;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.autoconfigure.reactive.ReactorContextPropagationConfigurer;
import com.logcollect.core.context.LogCollectThreadLocalAccessor;
import com.logcollect.core.degrade.DegradeFileManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoConfigurationBeanSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void beanFactories_createExpectedBeans() {
        LogCollectAsyncAutoConfiguration asyncAutoConfiguration = new LogCollectAsyncAutoConfiguration();
        LogCollectAsyncConfigurer asyncConfigurer = asyncAutoConfiguration.logCollectAsyncConfigurer();
        Executor executor = asyncConfigurer.getAsyncExecutor();
        assertThat(executor).isNotNull();

        assertThat(asyncAutoConfiguration.logCollectThreadPoolBPP()).isNotNull();

        TransactionalLogCollectHandlerWrapper txWrapper =
                new LogCollectTransactionAutoConfiguration().transactionalLogCollectHandlerWrapper();
        assertThat(txWrapper.executeInNewTransaction(() -> "ok")).isEqualTo("ok");
        txWrapper.executeInNewTransaction(() -> {
        });

        assertThat(new LogCollectReactorAutoConfiguration().reactorContextPropagationConfigurer()).isNotNull();
        assertThat(new LogCollectHealthAutoConfiguration().logCollectHealthIndicator()).isNotNull();

        HttpCodeStatusMapper mapper = new LogCollectHealthStatusAutoConfiguration().logCollectHttpCodeStatusMapper();
        assertThat(mapper.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(mapper.getStatusCode(Status.UP)).isEqualTo(200);

        LogCollectMetrics metrics = new LogCollectMetricsAutoConfiguration().logCollectMetrics(
                new SimpleMeterRegistry(), new LogCollectProperties(), null);
        assertThat(metrics).isNotNull();

        LogCollectThreadLocalAccessor accessor =
                new LogCollectContextPropagationAutoConfiguration().logCollectThreadLocalAccessor();
        assertThat(accessor).isNotNull();
    }

    @Test
    void degradeFileAutoConfiguration_createsInitializedManager() {
        LogCollectProperties properties = new LogCollectProperties();
        properties.getGlobal().getDegrade().getFile().setBaseDir(tempDir.toString());
        properties.getGlobal().getDegrade().getFile().setEncryptEnabled(false);

        ApplicationContext applicationContext = mock(ApplicationContext.class);
        Environment environment = mock(Environment.class);
        when(applicationContext.getEnvironment()).thenReturn(environment);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        DegradeFileManager manager = new DegradeFileAutoConfiguration().degradeFileManager(properties, applicationContext);
        assertThat(manager.isInitialized()).isTrue();
        assertThat(manager.getDiskFreeSpace()).isGreaterThan(0L);
    }

    @Test
    void reactorConfigurer_lifecycle_isSafe() throws Exception {
        ReactorContextPropagationConfigurer configurer = new LogCollectReactorAutoConfiguration()
                .reactorContextPropagationConfigurer();
        configurer.afterPropertiesSet();
        configurer.destroy();
    }
}

