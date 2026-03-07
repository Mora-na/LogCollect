package com.logcollect.samples.app;

import com.logcollect.samples.customasync.SampleCustomAsyncScenarioApplication;
import com.logcollect.samples.shared.SampleModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class SampleCustomAsyncScenarioLauncher {
    private static final Logger log = LoggerFactory.getLogger(SampleCustomAsyncScenarioLauncher.class);

    public void runScenario(SampleModuleProperties moduleProperties) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(SampleCustomAsyncScenarioApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.application.name=" + moduleProperties.getModuleId() + "-custom-async",
                        "sample.module-id=" + moduleProperties.getModuleId() + "-custom-async",
                        "sample.boot-version=" + moduleProperties.getBootVersion(),
                        "sample.logging-framework=" + moduleProperties.getLoggingFramework(),
                        "sample.auto-run=false"
                )
                .run();
        try {
            log.info("启动独立自定义 AsyncConfigurer 子上下文");
            context.getBean("sampleCustomAsyncScenarioRunner", Runnable.class).run();
        } finally {
            context.close();
        }
    }
}
