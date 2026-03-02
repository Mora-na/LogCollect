package com.logcollect.autoconfigure.jdbc;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * JDBC 处理器抽象基类。
 *
 * <p>提供 SINGLE/AGGREGATE 两种模式下的落库公共逻辑，以及参数化 SQL 拼装能力。
 */
public abstract class AbstractJdbcLogCollectHandler implements LogCollectHandler {
    private final JdbcTemplate jdbcTemplate;

    protected AbstractJdbcLogCollectHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void appendLog(LogCollectContext context, LogEntry entry) {
        String traceId = context == null ? entry.getTraceId() : context.getTraceId();
        Map<String, Object> params = buildInsertParams(traceId, entry.getContent(), entry.getLevel(), entry.getTime());
        insertWithParams(tableName(), params);
    }

    @Override
    public final void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        if (aggregatedLog == null) {
            return;
        }
        String flushId = aggregatedLog.getFlushId();
        if (flushId != null && !flushId.isEmpty() && existsFlushId(flushId)) {
            return;
        }
        doFlushAggregatedLog(context, aggregatedLog);
    }

    protected void doFlushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("trace_id", context == null ? null : context.getTraceId());
        params.put("content", aggregatedLog.getContent());
        params.put("entry_count", aggregatedLog.getEntryCount());
        params.put("max_level", aggregatedLog.getMaxLevel());
        params.put("is_final", aggregatedLog.isFinalFlush());
        params.put("last_time", aggregatedLog.getLastLogTime());
        insertWithParams(tableName(), params);
    }

    /**
     * 幂等校验扩展点：子类可按 flushId 判断是否已写入。
     *
     * @param flushId flush 唯一标识
     * @return true 表示已处理，当前 flush 将被跳过
     */
    protected boolean existsFlushId(String flushId) {
        return false;
    }

    protected abstract String tableName();

    /**
     * 新接口：构建参数。
     *
     * @param traceId 链路 ID
     * @param content 日志内容
     * @param level   日志级别
     * @param time    日志时间
     * @return 待写入字段参数映射
     */
    protected Map<String, Object> buildInsertParams(String traceId,
                                                    String content,
                                                    String level,
                                                    LocalDateTime time) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("trace_id", traceId);
        params.put("content", content);
        params.put("level", level);
        params.put("time", time);
        return params;
    }

    protected void insertWithParams(String table, Map<String, Object> params) {
        String tableName = sanitizeTableName(table);
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("insert params cannot be empty");
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" (");

        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> values = new ArrayList<Object>();

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            columns.add(sanitizeColumnName(entry.getKey()));
            placeholders.add("?");
            values.add(entry.getValue());
        }

        sql.append(columns).append(") VALUES (").append(placeholders).append(")");
        jdbcTemplate.update(sql.toString(), values.toArray());
    }

    private String sanitizeTableName(String name) {
        if (name == null || !name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid table name: " + name);
        }
        return name;
    }

    private String sanitizeColumnName(String name) {
        if (name == null || !name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid column name: " + name);
        }
        return name;
    }
}
