package com.logcollect.autoconfigure.jdbc;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import org.springframework.jdbc.core.JdbcTemplate;

public abstract class AbstractJdbcLogCollectHandler implements LogCollectHandler {
    private final JdbcTemplate jdbcTemplate;

    protected AbstractJdbcLogCollectHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void before(LogCollectContext context) {
    }

    @Override
    public void appendLog(LogCollectContext context, LogEntry entry) {
        String table = getTableName();
        validateTableName(table);
        String sql = "INSERT INTO " + table + " (trace_id, content, level, time) VALUES (?,?,?,?)";
        String traceId = context == null ? entry.getTraceId() : context.getTraceId();
        jdbcTemplate.update(sql, traceId, entry.getContent(), entry.getLevel(), entry.getTime());
    }

    @Override
    public void after(LogCollectContext context) {
    }

    protected abstract String getTableName();

    private void validateTableName(String table) {
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name");
        }
    }
}
