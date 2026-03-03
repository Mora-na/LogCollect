package com.logcollect.api.transaction;

/**
 * 事务执行器抽象接口。
 *
 * <p>在 logcollect-api 中定义，消除 adapter 模块对 Spring 事务管理器的反射依赖。
 * autoconfigure 模块提供基于 REQUIRES_NEW 的实现。
 */
@FunctionalInterface
public interface TransactionExecutor {

    /**
     * 无事务执行器：直接执行 action，不包装事务。
     */
    TransactionExecutor DIRECT = Runnable::run;

    /**
     * 在独立事务中执行指定操作。
     *
     * @param action 要在事务中执行的操作
     */
    void executeInNewTransaction(Runnable action);
}
