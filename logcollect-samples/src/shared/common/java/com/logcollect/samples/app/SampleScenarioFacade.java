package com.logcollect.samples.app;

import com.logcollect.samples.shared.SampleModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SampleScenarioFacade {
    private static final Logger log = LoggerFactory.getLogger(SampleScenarioFacade.class);

    private final SampleModuleProperties moduleProperties;
    private final SampleScenarioService scenarioService;
    private final SampleNestedOuterService nestedOuterService;
    private final SampleCustomAsyncScenarioLauncher customAsyncScenarioLauncher;
    private final RestTemplate restTemplate;
    private final ConfigurableApplicationContext applicationContext;

    public SampleScenarioFacade(SampleModuleProperties moduleProperties,
                                SampleScenarioService scenarioService,
                                SampleNestedOuterService nestedOuterService,
                                SampleCustomAsyncScenarioLauncher customAsyncScenarioLauncher,
                                RestTemplate restTemplate,
                                ConfigurableApplicationContext applicationContext) {
        this.moduleProperties = moduleProperties;
        this.scenarioService = scenarioService;
        this.nestedOuterService = nestedOuterService;
        this.customAsyncScenarioLauncher = customAsyncScenarioLauncher;
        this.restTemplate = restTemplate;
        this.applicationContext = applicationContext;
    }

    public void runAllScenarios() {
        log.info("启动 Sample 场景矩阵: {}", moduleProperties.describe());
        runScenario("场景01-同步方法", new Runnable() {
            @Override
            public void run() {
                scenarioService.runSynchronousScenario();
            }
        });
        runScenario("场景02-Spring@Async-默认 AsyncConfigurer", new Runnable() {
            @Override
            public void run() {
                scenarioService.runDefaultAsyncScenario();
            }
        });
        runScenario("场景03-Spring@Async-自定义 AsyncConfigurer", new Runnable() {
            @Override
            public void run() {
                customAsyncScenarioLauncher.runScenario(moduleProperties);
            }
        });
        runScenario("场景04-Spring ThreadPoolTaskExecutor", new Runnable() {
            @Override
            public void run() {
                scenarioService.runThreadPoolTaskExecutorScenario();
            }
        });
        runScenario("场景05-CompletableFuture + Spring 池", new Runnable() {
            @Override
            public void run() {
                scenarioService.runCompletableFutureWithSpringPoolScenario();
            }
        });
        runScenario("场景06A-WebFlux Mono", new Runnable() {
            @Override
            public void run() {
                scenarioService.runWebFluxMonoScenario();
            }
        });
        runScenario("场景06B-WebFlux Flux", new Runnable() {
            @Override
            public void run() {
                scenarioService.runWebFluxFluxScenario();
            }
        });
        runScenario("场景07-Spring Bean ExecutorService", new Runnable() {
            @Override
            public void run() {
                scenarioService.runSpringBeanExecutorServiceScenario();
            }
        });
        runScenario("场景08-手动 ExecutorService", new Runnable() {
            @Override
            public void run() {
                scenarioService.runManualExecutorServiceScenario();
            }
        });
        runScenario("场景09-new Thread()", new Runnable() {
            @Override
            public void run() {
                scenarioService.runNewThreadScenario();
            }
        });
        runScenario("场景10-第三方库回调", new Runnable() {
            @Override
            public void run() {
                scenarioService.runThirdPartyCallbackScenario();
            }
        });
        runScenario("场景11A-ForkJoinPool", new Runnable() {
            @Override
            public void run() {
                scenarioService.runForkJoinPoolScenario();
            }
        });
        runScenario("场景11B-parallelStream", new Runnable() {
            @Override
            public void run() {
                scenarioService.runParallelStreamScenario();
            }
        });
        runScenario("场景12-嵌套@LogCollect", new Runnable() {
            @Override
            public void run() {
                nestedOuterService.runScenario();
            }
        });
        runScenario("场景13-Servlet AsyncContext", new Runnable() {
            @Override
            public void run() {
                invokeServletAsyncScenario();
            }
        });
        log.info("Sample 场景矩阵执行完成: {}", moduleProperties.describe());
    }

    private void runScenario(String label, Runnable runnable) {
        try {
            log.info("开始执行 {}", label);
            runnable.run();
            log.info("执行完成 {}", label);
        } catch (Exception e) {
            log.error("{} 执行失败", label, e);
        }
    }

    private void invokeServletAsyncScenario() {
        if (!(applicationContext instanceof WebServerApplicationContext)) {
            log.warn("当前上下文不是 WebServerApplicationContext，跳过 Servlet AsyncContext 场景");
            return;
        }
        int port = ((WebServerApplicationContext) applicationContext).getWebServer().getPort();
        String response = restTemplate.getForObject("http://127.0.0.1:" + port + "/sample/servlet/async", String.class);
        log.info("Servlet AsyncContext 响应结果: {}", response);
    }
}

@Component
class SampleScenarioBootstrap {
    private static final Logger log = LoggerFactory.getLogger(SampleScenarioBootstrap.class);

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final SampleModuleProperties moduleProperties;
    private final SampleScenarioFacade scenarioFacade;
    private final ConfigurableApplicationContext applicationContext;

    SampleScenarioBootstrap(SampleModuleProperties moduleProperties,
                            SampleScenarioFacade scenarioFacade,
                            ConfigurableApplicationContext applicationContext) {
        this.moduleProperties = moduleProperties;
        this.scenarioFacade = scenarioFacade;
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!moduleProperties.isAutoRun()) {
            log.info("sample.auto-run=false，跳过自动执行");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scenarioFacade.runAllScenarios();
        if (moduleProperties.isExitAfterRun()) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }
}
