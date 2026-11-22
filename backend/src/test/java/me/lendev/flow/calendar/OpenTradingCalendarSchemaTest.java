package me.lendev.flow.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class OpenTradingCalendarSchemaTest {

    private Connection connection;

    @BeforeEach
    void initializeDatabase() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:open_api_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        try (var statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'Asia/Shanghai'");
        }
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2.sql"));
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void seedsUnpublishedDedicatedEndpoints() throws SQLException {
        Map<String, String> paths = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT endpoint_code, path_template, status, data_scope_type, http_method, api_version
                     FROM sys_open_api_endpoint
                     """)) {
            while (rows.next()) {
                paths.put(rows.getString("endpoint_code"), rows.getString("path_template"));
                assertThat(rows.getString("status")).isEqualTo("REGISTERED");
                assertThat(rows.getString("data_scope_type")).isEqualTo(
                        rows.getString("endpoint_code").startsWith("trading-calendar.") ? "MARKET" : "NONE");
                assertThat(rows.getString("http_method")).isEqualTo(
                        "document-generation.generations.create".equals(rows.getString("endpoint_code")) ? "POST" : "GET");
                assertThat(rows.getString("api_version")).isEqualTo("v1");
            }
        }
        assertThat(paths).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("trading-calendar.markets.read", "/open/v1/trading-calendar/markets"),
                Map.entry("trading-calendar.year.read", "/open/v1/trading-calendar/year"),
                Map.entry("trading-calendar.status.read", "/open/v1/trading-calendar/status"),
                Map.entry("trading-calendar.offset.read", "/open/v1/trading-calendar/offset"),
                Map.entry("field-definition.public.page.read", "/open/v1/field-definition"),
                Map.entry("field-definition.public.detail.read", "/open/v1/field-definition/{id}"),
                Map.entry("field-definition.public.metadata.read", "/open/v1/field-definition/metadata"),
                Map.entry("field-definition.public.dictionaries.read", "/open/v1/field-definition/dictionaries"),
                Map.entry("field-definition.public.dictionary-options.read", "/open/v1/field-definition/dictionaries/{id}/options"),
                Map.entry("document-generation.templates.read", "/open/v1/document-generation/templates"),
                Map.entry("document-generation.templates.current.read", "/open/v1/document-generation/templates/{id}/current"),
                Map.entry("document-generation.generations.create", "/open/v1/document-generation/generations"),
                Map.entry("document-generation.generations.read", "/open/v1/document-generation/generations/{id}"),
                Map.entry("document-generation.generations.file.read", "/open/v1/document-generation/generations/{id}/file")));
        assertThat(count("SELECT COUNT(*) FROM sys_open_api_app")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_open_api_app_secret")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_open_api_read_mapping")).isZero();
    }

    @Test
    void readMappingBelongsToASingleEndpoint() throws SQLException {
        execute("""
                INSERT INTO sys_open_api_endpoint
                    (id, endpoint_code, module_code, display_name, http_method, path_template, api_version,
                     data_scope_type, created_at, updated_at)
                VALUES (100, 'managed.calendar.status', 'trading-calendar', 'Calendar status', 'GET',
                        '/open/v1/managed/calendar-status', 'v1', 'MARKET', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        insertReadMapping(100);
        assertThat(count("SELECT COUNT(*) FROM sys_open_api_read_mapping WHERE endpoint_id = 100")).isOne();
        assertThatThrownBy(() -> insertReadMapping(100)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertReadMapping(101)).isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_open_api_read_mapping_endpoint");
        assertThatThrownBy(() -> execute("DELETE FROM sys_open_api_endpoint WHERE id = 100"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_open_api_read_mapping_endpoint");
    }

    @Test
    void storesSeedTimestampsInUtcRegardlessOfSessionTimeZone() throws SQLException {
        var utcNow = LocalDateTime.now(ZoneOffset.UTC);
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("""
                     SELECT MIN(created_at), MAX(updated_at) FROM sys_open_api_endpoint
                     """)) {
            row.next();
            assertThat(row.getObject(1, LocalDateTime.class)).isBetween(utcNow.minusMinutes(5), utcNow.plusMinutes(1));
            assertThat(row.getObject(2, LocalDateTime.class)).isBetween(utcNow.minusMinutes(5), utcNow.plusMinutes(1));
        }
    }

    @Test
    void seedsManagementPermissionsUnderTheOpenApiMenu() throws SQLException {
        assertThat(count("""
                SELECT COUNT(*) FROM permission menu JOIN permission parent ON parent.id = menu.parentId
                WHERE menu.code = 'OpenApiView' AND menu.path = '/pms/open-api' AND parent.code = 'SysMgt'
                """)).isOne();
        assertThat(count("""
                SELECT COUNT(*) FROM permission button JOIN permission menu ON menu.id = button.parentId
                WHERE menu.code = 'OpenApiView' AND button.type = 'BUTTON'
                  AND button.code IN ('OpenApiAppManage', 'OpenApiEndpointPublish', 'OpenApiGrantManage',
                                      'OpenApiSecretManage', 'OpenApiCallLogView')
                """)).isEqualTo(5);
    }

    @Test
    void rejectsPartialQuotaAndRestrictedGrantWithoutScope() throws SQLException {
        execute("""
                INSERT INTO sys_open_api_app (id, client_id, name, environment, rate_limit_count,
                    rate_limit_window_seconds, created_at, updated_at)
                VALUES (100, 'schema-test', 'Schema test', 'test', 10, 60, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertRejectedGrant("'ALL', NULL, 10, NULL");
        assertRejectedGrant("'ALL', NULL, NULL, 60");
        assertRejectedGrant("'RESTRICTED', NULL, NULL, NULL");
        assertRejectedGrant("'ALL', '{}', NULL, NULL");
        execute("""
                INSERT INTO sys_open_api_app_grant
                    (app_id, endpoint_id, status, data_scope_mode, created_at, updated_at)
                SELECT 100, id, 'GRANTED', 'ALL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM sys_open_api_endpoint WHERE endpoint_code = 'trading-calendar.markets.read'
                """);
        assertThatThrownBy(() -> execute("DELETE FROM sys_open_api_app WHERE id = 100"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_open_api_grant_app");
    }

    private void assertRejectedGrant(String values) {
        String sql = """
                INSERT INTO sys_open_api_app_grant
                    (app_id, endpoint_id, status, data_scope_mode, data_scope_json,
                     rate_limit_count, rate_limit_window_seconds, created_at, updated_at)
                SELECT 100, id, 'GRANTED', %s, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM sys_open_api_endpoint WHERE endpoint_code = 'trading-calendar.markets.read'
                """.formatted(values);
        assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class).hasMessageContaining("ck_open_api_grant_");
    }

    private int count(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void insertReadMapping(int endpointId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sys_open_api_read_mapping (endpoint_id, source_path, public_contract_json, source_contract_hash)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, endpointId);
            statement.setString(2, "/trading-calendar/status");
            statement.setString(3, "{\"fields\":[{\"name\":\"calendarDate\",\"type\":\"string\"}]}");
            statement.setString(4, "a".repeat(64));
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
