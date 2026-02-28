package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.jdbc.TransactionalLogCollectHandlerWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "org.springframework.transaction.support.TransactionTemplate")
public class LogCollectTransactionAutoConfiguration {
    @Bean
    public TransactionalLogCollectHandlerWrapper transactionalLogCollectHandlerWrapper() {
        return new TransactionalLogCollectHandlerWrapper();
    }
}
