package com.logcollect.autoconfigure.async;

import com.logcollect.core.context.LogCollectContextUtils;
import com.logcollect.core.context.LogCollectWrappedExecutor;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Spring 线程池后置处理器。
 *
 * <p>在 Bean 初始化前自动为线程池注入 {@link LogCollectTaskDecorator}，
 * 使 Spring 管理的线程池天然具备 LogCollect 上下文传播能力。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LogCollectThreadPoolBPP implements BeanPostProcessor {

    /**
     * 扫描线程池 Bean 并在初始化前附加 TaskDecorator。
     *
     * @param bean     当前 Bean
     * @param beanName Bean 名称
     * @return 原 Bean（就地增强）
     * @throws BeansException Bean 处理异常
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ThreadPoolTaskExecutor) {
            wrapTaskDecorator((ThreadPoolTaskExecutor) bean, beanName);
        }
        if (bean instanceof ThreadPoolTaskScheduler) {
            wrapTaskDecorator((ThreadPoolTaskScheduler) bean, beanName);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ThreadPoolTaskExecutor) {
            return wrapThreadPoolExecutorBean((ThreadPoolTaskExecutor) bean, beanName);
        }
        if (bean instanceof ThreadPoolTaskScheduler) {
            return wrapThreadPoolSchedulerBean((ThreadPoolTaskScheduler) bean, beanName);
        }
        if (!isEligibleForWrapping(bean, beanName)) {
            return bean;
        }
        try {
            if (bean instanceof ExecutorService) {
                return LogCollectContextUtils.wrapExecutorService((ExecutorService) bean);
            }
            if (bean instanceof Executor) {
                return LogCollectContextUtils.wrapExecutor((Executor) bean);
            }
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Failed to wrap executor bean: {}", beanName, e);
        } catch (Error e) {
            throw e;
        }
        return bean;
    }

    private Object wrapThreadPoolExecutorBean(ThreadPoolTaskExecutor executor, String beanName) {
        if (!isInitialized(executor)) {
            return executor;
        }
        return createTaskSubmissionProxy(executor, beanName);
    }

    private Object wrapThreadPoolSchedulerBean(ThreadPoolTaskScheduler scheduler, String beanName) {
        if (!isInitialized(scheduler)) {
            return scheduler;
        }
        return createTaskSubmissionProxy(scheduler, beanName);
    }

    /**
     * 为 ThreadPoolTaskExecutor 注入装饰器。
     */
    private void wrapTaskDecorator(ThreadPoolTaskExecutor executor, String beanName) {
        try {
            if (isInitialized(executor)) {
                return;
            }
            TaskDecorator existing = getTaskDecoratorReflective(executor);
            TaskDecorator lc = new LogCollectTaskDecorator();
            TaskDecorator chained = existing == null ? lc : chain(existing, lc);
            setTaskDecoratorReflective(executor, chained);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to wrap TaskDecorator on: {}", beanName, t);
        }
    }

    /**
     * 为 ThreadPoolTaskScheduler 注入装饰器。
     */
    private void wrapTaskDecorator(ThreadPoolTaskScheduler scheduler, String beanName) {
        try {
            if (isInitialized(scheduler)) {
                return;
            }
            TaskDecorator existing = getTaskDecoratorReflective(scheduler);
            TaskDecorator lc = new LogCollectTaskDecorator();
            TaskDecorator chained = existing == null ? lc : chain(existing, lc);
            setTaskDecoratorReflective(scheduler, chained);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to wrap TaskDecorator on: {}", beanName, t);
        }
    }

    /**
     * 链式组合两个装饰器，保留用户已有逻辑并追加 LogCollect 装饰。
     *
     * @param first  原装饰器
     * @param second 新装饰器
     * @return 组合后的装饰器
     */
    private TaskDecorator chain(final TaskDecorator first, final TaskDecorator second) {
        return new TaskDecorator() {
            @Override
            public Runnable decorate(Runnable runnable) {
                return second.decorate(first.decorate(runnable));
            }
        };
    }

    /**
     * 反射读取线程池当前 TaskDecorator。
     *
     * @param target 线程池对象
     * @return 当前装饰器；读取失败返回 null
     */
    private TaskDecorator getTaskDecoratorReflective(Object target) {
        try {
            java.lang.reflect.Method getter = target.getClass().getMethod("getTaskDecorator");
            Object val = getter.invoke(target);
            return (TaskDecorator) val;
        } catch (Throwable ignore) {
            // fall through
        }
        try {
            java.lang.reflect.Field field = findField(target.getClass(), "taskDecorator");
            if (field != null) {
                field.setAccessible(true);
                return (TaskDecorator) field.get(target);
            }
        } catch (Throwable ignore) {
            // ignore
        }
        return null;
    }

    private boolean isInitialized(ThreadPoolTaskExecutor executor) {
        try {
            return executor.getThreadPoolExecutor() != null;
        } catch (IllegalStateException ex) {
            return false;
        } catch (Throwable ex) {
            return false;
        }
    }

    private boolean isInitialized(ThreadPoolTaskScheduler scheduler) {
        try {
            return scheduler.getScheduledExecutor() != null;
        } catch (IllegalStateException ex) {
            return false;
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * 反射设置 TaskDecorator。
     *
     * <p>优先调用 setter；若无 setter 则回退到字段写入。
     *
     * @param target    线程池对象
     * @param decorator 待设置装饰器
     * @throws Exception 反射异常
     */
    private void setTaskDecoratorReflective(Object target, TaskDecorator decorator) throws Exception {
        try {
            java.lang.reflect.Method setter = target.getClass().getMethod("setTaskDecorator", TaskDecorator.class);
            setter.invoke(target, decorator);
            return;
        } catch (NoSuchMethodException ignore) {
            // fall through
        }
        java.lang.reflect.Field field = findField(target.getClass(), "taskDecorator");
        if (field != null) {
            field.setAccessible(true);
            field.set(target, decorator);
        }
    }

    /**
     * 在类继承链中查找指定字段。
     *
     * @param type 起始类型
     * @param name 字段名
     * @return 字段对象；未找到返回 null
     */
    private java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private boolean isEligibleForWrapping(Object bean, String beanName) {
        if (bean == null) {
            return false;
        }
        if (bean instanceof LogCollectWrappedExecutor) {
            return false;
        }
        if (bean instanceof ThreadPoolTaskExecutor || bean instanceof TaskScheduler) {
            return false;
        }
        return beanName == null || !beanName.startsWith("logCollect");
    }

    private Object createTaskSubmissionProxy(Object bean, String beanName) {
        try {
            ProxyFactory factory = new ProxyFactory(bean);
            factory.setProxyTargetClass(true);
            factory.addInterface(LogCollectWrappedExecutor.class);
            factory.addAdvice(new MethodInterceptor() {
                @Override
                public Object invoke(MethodInvocation invocation) throws Throwable {
                    Method method = invocation.getMethod();
                    if (isObjectMethod(method)) {
                        return invocation.proceed();
                    }
                    Object[] originalArguments = invocation.getArguments();
                    Object[] wrappedArguments = wrapTaskArguments(originalArguments);
                    if (wrappedArguments == originalArguments) {
                        return invocation.proceed();
                    }
                    return invokeMethod(bean, method, wrappedArguments);
                }
            });
            return factory.getProxy();
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to create thread-pool proxy: {}", beanName, t);
            return bean;
        }
    }

    private Object[] wrapTaskArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return arguments;
        }
        Object[] wrapped = null;
        for (int i = 0; i < arguments.length; i++) {
            Object candidate = arguments[i];
            Object replacement = wrapTaskArgument(candidate);
            if (replacement == candidate) {
                continue;
            }
            if (wrapped == null) {
                wrapped = arguments.clone();
            }
            wrapped[i] = replacement;
        }
        return wrapped == null ? arguments : wrapped;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object wrapTaskArgument(Object candidate) {
        if (candidate instanceof Runnable) {
            return LogCollectContextUtils.wrapRunnable((Runnable) candidate);
        }
        if (candidate instanceof Callable) {
            return LogCollectContextUtils.wrapCallable((Callable) candidate);
        }
        if (candidate instanceof Collection) {
            Collection<?> tasks = (Collection<?>) candidate;
            if (tasks.isEmpty()) {
                return candidate;
            }
            boolean callableCollection = true;
            for (Object task : tasks) {
                if (!(task instanceof Callable)) {
                    callableCollection = false;
                    break;
                }
            }
            if (callableCollection) {
                Collection<Callable<Object>> wrappedTasks = new ArrayList<Callable<Object>>(tasks.size());
                for (Object task : tasks) {
                    wrappedTasks.add(LogCollectContextUtils.wrapCallable((Callable<Object>) task));
                }
                return wrappedTasks;
            }
        }
        return candidate;
    }

    private Object invokeMethod(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }

    private boolean isObjectMethod(Method method) {
        return method != null && method.getDeclaringClass() == Object.class;
    }
}
