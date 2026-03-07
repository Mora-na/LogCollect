package com.logcollect.samples.app;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Cache;
import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextUtils;
import com.logcollect.samples.shared.SampleScenarioLogCollectHandler;
import com.logcollect.samples.shared.SampleScenarioSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

@Service
public class SampleScenarioService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(SampleScenarioService.class);

    private final ThreadPoolTaskExecutor sampleThreadPoolTaskExecutor;
    private final ExecutorService sampleBeanExecutorService;
    private final SampleDefaultAsyncWorker sampleDefaultAsyncWorker;
    private final ExecutorService manualExecutorService =
            LogCollectContextUtils.wrapExecutorService(Executors.newFixedThreadPool(2));
    private final ExecutorService thirdPartyExecutor = Executors.newSingleThreadExecutor();

    public SampleScenarioService(ThreadPoolTaskExecutor sampleThreadPoolTaskExecutor,
                                 ExecutorService sampleBeanExecutorService,
                                 SampleDefaultAsyncWorker sampleDefaultAsyncWorker) {
        this.sampleThreadPoolTaskExecutor = sampleThreadPoolTaskExecutor;
        this.sampleBeanExecutorService = sampleBeanExecutorService;
        this.sampleDefaultAsyncWorker = sampleDefaultAsyncWorker;
    }

    @Override
    public void destroy() {
        manualExecutorService.shutdownNow();
        thirdPartyExecutor.shutdownNow();
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runSynchronousScenario() {
        String code = "01";
        String title = "同步方法";
        SampleScenarioSupport.enterScenario(log, code, title, 5);
        SampleScenarioSupport.step(log, code, title, "01", "进入同步入口，traceId={}", LogCollectContext.getCurrentTraceId());
        synchronousGateway(code, title);
        SampleScenarioSupport.step(log, code, title, "04", "同步调用链执行完成");
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runDefaultAsyncScenario() {
        String code = "02";
        String title = "Spring@Async-默认 AsyncConfigurer";
        SampleScenarioSupport.enterScenario(log, code, title, 5);
        SampleScenarioSupport.step(log, code, title, "01", "提交两个默认 @Async 任务");
        CompletableFuture<String> first = sampleDefaultAsyncWorker.runFragment(code, title, "02", "默认任务A");
        CompletableFuture<String> second = sampleDefaultAsyncWorker.runFragment(code, title, "03", "默认任务B");
        String firstResult = SampleScenarioSupport.get(first, title + "-任务A");
        String secondResult = SampleScenarioSupport.get(second, title + "-任务B");
        SampleScenarioSupport.step(log, code, title, "04", "默认 @Async 任务全部完成：{}, {}", firstResult, secondResult);
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runThreadPoolTaskExecutorScenario() {
        String code = "04";
        String title = "Spring ThreadPoolTaskExecutor";
        SampleScenarioSupport.enterScenario(log, code, title, 4);
        final CountDownLatch latch = new CountDownLatch(2);
        sampleThreadPoolTaskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                SampleScenarioSupport.step(log, code, title, "02", "TaskExecutor 子任务A在线程 {} 上执行", Thread.currentThread().getName());
                latch.countDown();
            }
        });
        sampleThreadPoolTaskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                SampleScenarioSupport.step(log, code, title, "03", "TaskExecutor 子任务B在线程 {} 上执行", Thread.currentThread().getName());
                latch.countDown();
            }
        });
        SampleScenarioSupport.await(latch, title);
        SampleScenarioSupport.step(log, code, title, "04", "ThreadPoolTaskExecutor 场景完成");
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runCompletableFutureWithSpringPoolScenario() {
        String code = "05";
        String title = "CompletableFuture + Spring池";
        SampleScenarioSupport.enterScenario(log, code, title, 4);
        CompletableFuture<String> inventory = CompletableFuture.supplyAsync(() -> {
            SampleScenarioSupport.step(log, code, title, "02", "CompletableFuture 库存分支在线程 {} 上执行", Thread.currentThread().getName());
            return "库存校验完成";
        }, sampleThreadPoolTaskExecutor);
        CompletableFuture<String> pricing = CompletableFuture.supplyAsync(() -> {
            SampleScenarioSupport.step(log, code, title, "03", "CompletableFuture 价格分支在线程 {} 上执行", Thread.currentThread().getName());
            return "价格计算完成";
        }, sampleThreadPoolTaskExecutor);
        CompletableFuture<Void> all = CompletableFuture.allOf(inventory, pricing);
        SampleScenarioSupport.get(all, title + "-allOf");
        SampleScenarioSupport.step(log, code, title, "04", "合并结果：{} / {}", inventory.join(), pricing.join());
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runWebFluxMonoScenario() {
        String code = "06A";
        String title = "WebFlux Mono";
        SampleScenarioSupport.enterScenario(log, code, title, 5);
        String result = Mono.just("订单草稿")
                .doOnNext(value -> SampleScenarioSupport.step(log, code, title, "01", "Mono 装配阶段拿到数据={}", value))
                .publishOn(Schedulers.boundedElastic())
                .map(value -> {
                    SampleScenarioSupport.step(log, code, title, "02", "Mono publishOn 后线程={}，traceId={}",
                            Thread.currentThread().getName(), LogCollectContext.getCurrentTraceId());
                    return value + "-已校验";
                })
                .doOnNext(value -> SampleScenarioSupport.step(log, code, title, "03", "Mono 结果={}", value))
                .block();
        SampleScenarioSupport.step(log, code, title, "04", "Mono 场景结束，最终结果={}", result);
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runWebFluxFluxScenario() {
        String code = "06B";
        String title = "WebFlux Flux";
        SampleScenarioSupport.enterScenario(log, code, title, 5);
        List<String> result = Flux.just("日志分片A", "日志分片B", "日志分片C")
                .publishOn(Schedulers.parallel())
                .map(value -> {
                    SampleScenarioSupport.step(log, code, title, "02", "Flux 元素={} 在线程 {} 上执行", value, Thread.currentThread().getName());
                    return value + "-已聚合";
                })
                .collectList()
                .block();
        SampleScenarioSupport.step(log, code, title, "03", "Flux 场景结束，结果条数={}", result == null ? 0 : result.size());
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runSpringBeanExecutorServiceScenario() {
        String code = "07";
        String title = "Spring Bean ExecutorService";
        SampleScenarioSupport.enterScenario(log, code, title, 4);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            SampleScenarioSupport.step(log, code, title, "02", "Spring Bean ExecutorService 任务A线程={}", Thread.currentThread().getName());
            return "Bean任务A";
        }, sampleBeanExecutorService);
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
            SampleScenarioSupport.step(log, code, title, "03", "Spring Bean ExecutorService 任务B线程={}", Thread.currentThread().getName());
            return "Bean任务B";
        }, sampleBeanExecutorService);
        SampleScenarioSupport.get(CompletableFuture.allOf(first, second), title);
        SampleScenarioSupport.step(log, code, title, "04", "Bean ExecutorService 场景完成：{}, {}", first.join(), second.join());
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runManualExecutorServiceScenario() {
        String code = "08";
        String title = "手动 ExecutorService";
        SampleScenarioSupport.enterScenario(log, code, title, 3);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            SampleScenarioSupport.step(log, code, title, "02", "手动包装线程池在线程 {} 上执行", Thread.currentThread().getName());
            return "手动线程池执行完成";
        }, manualExecutorService);
        SampleScenarioSupport.step(log, code, title, "03", "手动线程池结果={}", SampleScenarioSupport.get(future, title));
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runNewThreadScenario() {
        String code = "09";
        String title = "new Thread()";
        SampleScenarioSupport.enterScenario(log, code, title, 3);
        final CountDownLatch latch = new CountDownLatch(1);
        Thread thread = LogCollectContextUtils.newThread(new Runnable() {
            @Override
            public void run() {
                SampleScenarioSupport.step(log, code, title, "02", "new Thread 包装后线程={}，traceId={}",
                        Thread.currentThread().getName(), LogCollectContext.getCurrentTraceId());
                latch.countDown();
            }
        }, "sample-new-thread");
        thread.start();
        SampleScenarioSupport.await(latch, title);
        SampleScenarioSupport.join(thread, title);
        SampleScenarioSupport.step(log, code, title, "03", "new Thread 场景完成");
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runThirdPartyCallbackScenario() {
        String code = "10";
        String title = "第三方库回调-Caffeine";
        SampleScenarioSupport.enterScenario(log, code, title, 4);
        final CountDownLatch latch = new CountDownLatch(1);
        Cache<String, String> cache = Caffeine.newBuilder()
                .executor(LogCollectContextUtils.wrapExecutor(thirdPartyExecutor))
                .removalListener((String key, String value, RemovalCause cause) -> {
                    SampleScenarioSupport.step(log, code, title, "02",
                            "Caffeine RemovalListener 回调触发，key={}，cause={}", key, cause);
                    latch.countDown();
                })
                .build();
        cache.put("sample-third-party", "payload");
        SampleScenarioSupport.step(log, code, title, "01", "写入第三方缓存并触发失效");
        cache.invalidate("sample-third-party");
        SampleScenarioSupport.await(latch, title);
        SampleScenarioSupport.step(log, code, title, "03", "第三方库回调场景完成");
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runForkJoinPoolScenario() {
        String code = "11A";
        String title = "ForkJoinPool";
        SampleScenarioSupport.enterScenario(log, code, title, 3);
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            SampleScenarioSupport.get(pool.submit(LogCollectContextUtils.wrapRunnable(new Runnable() {
                @Override
                public void run() {
                    SampleScenarioSupport.step(log, code, title, "02", "ForkJoinPool 任务在线程 {} 上执行",
                            Thread.currentThread().getName());
                }
            })), title);
        } finally {
            pool.shutdownNow();
        }
        SampleScenarioSupport.step(log, code, title, "03", "ForkJoinPool 场景完成");
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runParallelStreamScenario() {
        String code = "11B";
        String title = "parallelStream";
        SampleScenarioSupport.enterScenario(log, code, title, 5);
        Arrays.asList(1, 2, 3).parallelStream().forEach(LogCollectContextUtils.wrapConsumer(new Consumer<Integer>() {
            @Override
            public void accept(Integer value) {
                SampleScenarioSupport.step(log, code, title, "02", "parallelStream 元素={} 在线程 {} 上执行",
                        value, Thread.currentThread().getName());
            }
        }));
        SampleScenarioSupport.step(log, code, title, "03", "parallelStream 场景完成");
    }

    private void synchronousGateway(String code, String title) {
        SampleScenarioSupport.step(log, code, title, "02", "同步网关层处理请求");
        synchronousRepository(code, title);
    }

    private void synchronousRepository(String code, String title) {
        SampleScenarioSupport.step(log, code, title, "03", "同步仓储层落库模拟完成");
    }
}

@Service
class SampleDefaultAsyncWorker {
    private static final Logger log = LoggerFactory.getLogger(SampleDefaultAsyncWorker.class);

    @Async
    public CompletableFuture<String> runFragment(String code, String title, String step, String fragmentName) {
        SampleScenarioSupport.step(log, code, title, step, "默认 @Async 分片={} 在线程 {} 上执行",
                fragmentName, Thread.currentThread().getName());
        return CompletableFuture.completedFuture(fragmentName);
    }
}

@Service
class SampleNestedOuterService {
    private static final Logger log = LoggerFactory.getLogger(SampleNestedOuterService.class);

    private final SampleNestedInnerService sampleNestedInnerService;

    SampleNestedOuterService(SampleNestedInnerService sampleNestedInnerService) {
        this.sampleNestedInnerService = sampleNestedInnerService;
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runScenario() {
        String code = "12A";
        String title = "嵌套@LogCollect-外层";
        SampleScenarioSupport.enterScenario(log, code, title, 3);
        SampleScenarioSupport.step(log, code, title, "01", "外层方法开始，准备调用内层 @LogCollect");
        sampleNestedInnerService.runInnerScenario();
        SampleScenarioSupport.step(log, code, title, "02", "内层 @LogCollect 返回，外层上下文已自动恢复");
    }
}

@Service
class SampleNestedInnerService {
    private static final Logger log = LoggerFactory.getLogger(SampleNestedInnerService.class);

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runInnerScenario() {
        String code = "12B";
        String title = "嵌套@LogCollect-内层";
        SampleScenarioSupport.enterScenario(log, code, title, 3);
        SampleScenarioSupport.step(log, code, title, "01", "内层风控校验执行中");
        SampleScenarioSupport.step(log, code, title, "02", "内层风控校验结束");
    }
}
