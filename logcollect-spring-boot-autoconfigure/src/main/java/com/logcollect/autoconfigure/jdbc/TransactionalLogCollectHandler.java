package com.logcollect.autoconfigure.jdbc;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

public abstract class TransactionalLogCollectHandler extends AbstractJdbcLogCollectHandler {

    @Autowired
    private TransactionalLogCollectHandlerWrapper txWrapper;

    protected TransactionalLogCollectHandler(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    @Override
    public final void appendLog(LogCollectContext context, LogEntry entry) {
        txWrapper.executeInNewTransaction(() -> {
            TransactionalLogCollectHandler.super.appendLog(context, entry);
            return null;
        });
    }
}
