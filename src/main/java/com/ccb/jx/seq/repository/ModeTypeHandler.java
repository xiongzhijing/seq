
package com.ccb.jx.seq.repository;

import com.ccb.jx.seq.model.Mode;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Mode 枚举类型处理器。
 * <p>
 * 支持大小写不敏感的枚举映射，兼容数据库中存储的 "STRICT"、"strict"、"Strict" 等格式。
 * 避免因数据迁移或手动 SQL 插入时的大小写不一致导致 {@link IllegalArgumentException}。
 * </p>
 *
 * @author XZJ
 */
public class ModeTypeHandler extends BaseTypeHandler<Mode> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Mode parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public Mode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toMode(rs.getString(columnName));
    }

    @Override
    public Mode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toMode(rs.getString(columnIndex));
    }

    @Override
    public Mode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toMode(cs.getString(columnIndex));
    }

    private Mode toMode(String value) {
        if (value == null) {
            return null;
        }
        return Mode.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
