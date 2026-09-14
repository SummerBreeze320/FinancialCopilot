package com.financial.copilot.data.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <h1>PostgreSQL 数组类型 (text[] / varchar[]) 与 Java List&lt;String&gt; 类型转换器</h1>
 * <p>
 * 用于 MyBatis-Plus 实体中存储或读取 PostgreSQL 原生数组字段（如别名 aliases、支持用途 supported_usage 等）。
 * </p>
 *
 * @author FinancialCopilot
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class StringListTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        Connection conn = ps.getConnection();
        Array array = conn.createArrayOf("text", parameter.toArray());
        ps.setArray(i, array);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return extractList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return extractList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return extractList(cs.getArray(columnIndex));
    }

    private List<String> extractList(Array array) throws SQLException {
        if (array == null) {
            return Collections.emptyList();
        }
        Object arr = array.getArray();
        if (arr instanceof String[]) {
            return Arrays.asList((String[]) arr);
        }
        return Collections.emptyList();
    }
}
