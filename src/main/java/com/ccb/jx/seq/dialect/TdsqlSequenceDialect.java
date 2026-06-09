
package com.ccb.jx.seq.dialect;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.SequenceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

/**
 * TDSQL 原生 Sequence 方言实现。
 * <p>
 * 使用 TDSQL 特有语法：
 * <ul>
 *   <li>获取值：{@code SELECT tdsql_nextval(seqName)}</li>
 *   <li>创建：{@code CREATE TDSQL_SEQUENCE ... START WITH ...}</li>
 *   <li>删除：{@code DROP TDSQL_SEQUENCE seqName}</li>
 *   <li>检查：{@code SELECT tdsql_lastval(seqName)}</li>
 * </ul>
 * </p>
 *
 * @author XZJ
 */
public class TdsqlSequenceDialect implements NativeSequenceDialect {

    private static final Logger log = LoggerFactory.getLogger(TdsqlSequenceDialect.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造 TDSQL 方言实现。
     *
     * @param jdbcTemplate 序列数据源的 JdbcTemplate
     */
    public TdsqlSequenceDialect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long nextVal(SequenceConfig config) {
        String seqName = config.getSeqName();
        try {
            Long value = jdbcTemplate.queryForObject(
                    "SELECT tdsql_nextval(?)",
                    Long.class,
                    seqName);
            if (value == null) {
                throw new SequenceException(SequenceErrorCode.NATIVE_SEQ_ERROR,
                        "tdsql_nextval returned null for: " + seqName);
            }
            log.debug("[NATIVE] nextVal: seqName={}, value={}", seqName, value);
            return value;
        } catch (DataAccessException e) {
            throw new SequenceException(SequenceErrorCode.NATIVE_SEQ_ERROR,
                    "tdsql_nextval failed for: " + seqName + ", error: " + e.getMessage(), e);
        }
    }

    @Override
    public void createSequence(SequenceConfig config) {
        String seqName = config.getSeqName();
        long startWith = config.getStartWith();
        long minValue = config.getMinValue();
        long maxValue = config.getMaxValue();
        int incrementBy = config.getIncrementBy();
        boolean cycle = config.getCycle() == 1;

        StringBuilder ddl = new StringBuilder("CREATE TDSQL_SEQUENCE ");
        ddl.append(seqName);
        ddl.append(" START WITH ").append(startWith);
        ddl.append(" TDSQL_MINVALUE ").append(minValue);

        // maxValue 接近 Long.MAX_VALUE 时使用 NOMAXVALUE
        if (maxValue >= Long.MAX_VALUE - 1) {
            ddl.append(" TDSQL_NOMAXVALUE");
        } else {
            ddl.append(" TDSQL_MAXVALUE ").append(maxValue);
        }

        ddl.append(" TDSQL_INCREMENT BY ").append(incrementBy);

        if (cycle) {
            ddl.append(" TDSQL_CYCLE");
        } else {
            ddl.append(" TDSQL_NOCYCLE");
        }

        String sql = ddl.toString();
        log.info("[NATIVE] Creating sequence: seqName={}, sql={}", seqName, sql);

        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException e) {
            throw new SequenceException(SequenceErrorCode.NATIVE_SEQ_ERROR,
                    "Failed to create TDSQL sequence: " + seqName + ", sql: " + sql, e);
        }
    }

    @Override
    public void dropSequence(String seqName) {
        String sql = "DROP TDSQL_SEQUENCE " + seqName;
        log.info("[NATIVE] Dropping sequence: seqName={}, sql={}", seqName, sql);

        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException e) {
            throw new SequenceException(SequenceErrorCode.NATIVE_SEQ_ERROR,
                    "Failed to drop TDSQL sequence: " + seqName, e);
        }
    }

    @Override
    public boolean sequenceExists(String seqName) {
        try {
            // 尝试调用 tdsql_lastval，如果 Sequence 不存在会抛异常
            jdbcTemplate.queryForObject("SELECT tdsql_lastval(?)", Long.class, seqName);
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    @Override
    public boolean isSequenceNotExist(SequenceException e) {
        if (e == null || e.getCause() == null) {
            return false;
        }
        Throwable cause = e.getCause();
        // 遍历 cause 链
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null) {
                // TDSQL 返回的错误码和消息
                String upper = msg.toUpperCase();
                if (upper.contains("NOT EXIST") || upper.contains("NOT EXISTS")
                        || upper.contains("SEQUENCE") && upper.contains("NOT FOUND")
                        || upper.contains("1146") // Table/sequence doesn't exist
                        || upper.contains("ER_NO_SUCH_TABLE")) {
                    return true;
                }
            }
            // SQLException 可以获取 SQLState
            if (cause instanceof SQLException) {
                String sqlState = ((SQLException) cause).getSQLState();
                if ("42S02".equals(sqlState) || "42S22".equals(sqlState)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
