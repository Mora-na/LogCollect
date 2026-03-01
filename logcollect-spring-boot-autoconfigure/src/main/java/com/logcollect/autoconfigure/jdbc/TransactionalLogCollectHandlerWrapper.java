package com.logcollect.autoconfigure.jdbc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Callable;

public class TransactionalLogCollectHandlerWrapper {

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public <T> T executeInNewTransaction(Callable<T> action) {
        if (transactionManager == null) {
            try {
                return action.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> {
            try {
                return action.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void executeInNewTransaction(Runnable action) {
        executeInNewTransaction(() -> {
            action.run();
            return null;
        });
    }
}
