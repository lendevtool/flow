package me.lendev.flow.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 验证专用接口增量登记、初始化及全量脚本能保持一致且可重复执行。
 */
class OpenFieldSchemaTest {
    private static final String FIELD_SCOPE = "field-definition.public.%";
    private static final String FIELD_DEFINITION_SEED = "db/seed/field-definition-open-api-test.sql";
    private Connection connection;

    @BeforeEach
    void initialize() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:open_field_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2.sql"));
    }

    @AfterEach
    void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void incrementallyRegistersFiveEndpointsWithoutChangingPublishedConfiguration() throws Exception {
        connection.createStatement().executeUpdate("DELETE FROM sys_open_api_endpoint WHERE module_code = 'field-definition'");
        var seed = mysqlSeed(FIELD_DEFINITION_SEED);
        ScriptUtils.executeSqlScript(connection, seed);
        long id = fieldEndpointId();
        connection.createStatement().executeUpdate("UPDATE sys_open_api_endpoint SET display_name='自定义元数据', status='PUBLISHED', version=4 WHERE id=" + id);
        ScriptUtils.executeSqlScript(connection, seed);
        assertThat(fieldEndpointId()).isEqualTo(id);
        assertThat(countFields()).isEqualTo(5);
        try (var result = connection.createStatement().executeQuery("SELECT display_name, status, version FROM sys_open_api_endpoint WHERE id=" + id)) {
            result.next();
            assertThat(result.getString(1)).isEqualTo("自定义元数据");
            assertThat(result.getString(2)).isEqualTo("PUBLISHED");
            assertThat(result.getLong(3)).isEqualTo(4);
        }
        try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM sys_open_api_read_mapping")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void rejectsRouteConflictsWithoutPartiallyInsertingEndpoints() throws Exception {
        connection.createStatement().executeUpdate("DELETE FROM sys_open_api_endpoint WHERE module_code = 'field-definition'");
        connection.createStatement().executeUpdate("""
                INSERT INTO sys_open_api_endpoint
                    (endpoint_code, module_code, display_name, http_method, path_template,
                     api_version, data_scope_type, created_at, updated_at)
                VALUES ('test.conflicting-route', 'test', '冲突路由', 'GET', '/open/v1/field-definition/metadata',
                        'v1', 'NONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        var seed = mysqlSeed(FIELD_DEFINITION_SEED);
        assertThrows(ScriptException.class, () -> ScriptUtils.executeSqlScript(connection, seed));
        assertThat(countFields()).isZero();
    }

    @Test
    void fullAnnotationSeedMatchesTheNineInitializedDedicatedContracts() throws Exception {
        var initialized = contracts();
        connection.createStatement().executeUpdate("DELETE FROM sys_open_api_endpoint");
        var seed = mysqlSeed("db/seed/open-api-annotated-endpoints.sql");
        ScriptUtils.executeSqlScript(connection, seed);
        ScriptUtils.executeSqlScript(connection, seed);
        assertThat(contracts()).isEqualTo(initialized);
    }

    private String contracts() throws SQLException {
        var values = new StringBuilder();
        try (var result = connection.createStatement().executeQuery("""
                SELECT endpoint_code, module_code, http_method, path_template, api_version, data_scope_type, status
                FROM sys_open_api_endpoint ORDER BY endpoint_code
                """)) {
            while (result.next()) {
                for (int column = 1; column <= 7; column++) {
                    values.append(result.getString(column)).append('|');
                }
                values.append('\n');
            }
        }
        return values.toString();
    }

    private long fieldEndpointId() throws SQLException {
        try (var result = connection.createStatement().executeQuery("SELECT id FROM sys_open_api_endpoint WHERE endpoint_code='field-definition.public.metadata.read'")) {
            result.next();
            return result.getLong(1);
        }
    }

    private int countFields() throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM sys_open_api_endpoint WHERE endpoint_code LIKE ?")) {
            statement.setString(1, FIELD_SCOPE);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static ByteArrayResource mysqlSeed(String resource) throws Exception {
        String sql = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                .replace("UTC_TIMESTAMP(3)", "CAST(FORMATDATETIME(CURRENT_TIMESTAMP(3), 'yyyy-MM-dd HH:mm:ss.SSS', 'en', 'UTC') AS TIMESTAMP(3))");
        return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8));
    }
}
