package me.lendev.flow.param.support;

import java.sql.*;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MyBatis 的 {@code JsonNode} 类型处理器。 MySQL 原生 JSON 列与 H2 的 CLOB 列在 JDBC 层都表现为字符串， 因此统一按字符串读写即可共享同一套 JSON 序列化边界。
 */
public class ParamJsonTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode value, JdbcType type) throws SQLException {
        ps.setString(i, JSON.writeValueAsString(value));
    }

    private JsonNode parse(String value) {
        return value == null ? null : JSON.readTree(value);
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String column) throws SQLException {
        return parse(rs.getString(column));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int index) throws SQLException {
        return parse(rs.getString(index));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement statement, int index) throws SQLException {
        return parse(statement.getString(index));
    }
}

