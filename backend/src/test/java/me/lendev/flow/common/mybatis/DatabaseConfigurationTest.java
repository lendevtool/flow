package me.lendev.flow.common.mybatis;

import static org.junit.jupiter.api.Assertions.*;

import com.mybatisflex.core.FlexGlobalConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

class DatabaseConfigurationTest {

    private DataSourceProperties mysql(String url, String username, String password) {
        var properties = new DataSourceProperties();
        properties.setUrl(url);
        properties.setUsername(username);
        properties.setPassword(password);
        return properties;
    }

    private void validate(DataSourceProperties properties) {
        new MybatisFlexConfigure().validateDatabaseConfiguration(properties).customize(new FlexGlobalConfig());
    }

    @Test
    void unresolvedJdbcUrlFailsClearlyBeforeDialectSelection() {
        var properties = mysql("${DB_URL}", "flow", "private-test-value");
        var error = assertThrows(IllegalStateException.class,
                () -> validate(properties));
        assertTrue(error.getMessage().contains("DB_URL"));
        assertFalse(error.getMessage().contains("private-test-value"));
    }

    @Test
    void nonJdbcMysqlUrlIsRejectedRatherThanProducingNullDbType() {
        var properties = mysql("mysql://host:3316/flow_uat", "flow", "private-test-value");
        var error = assertThrows(IllegalStateException.class,
                () -> validate(properties));
        assertTrue(error.getMessage().contains("jdbc:"));
    }

    @Test
    void unsupportedJdbcSchemeIsRejected() {
        var properties = mysql("jdbc:unknown://host/database", "flow", "private-test-value");
        assertThrows(IllegalStateException.class,
                () -> validate(properties));
    }

    @Test
    void missingMysqlUsernameIsReportedByName() {
        var properties = mysql("jdbc:mysql://host:3316/flow_uat", "${DB_USERNAME}", "private-test-value");
        var error = assertThrows(IllegalStateException.class,
                () -> validate(properties));
        assertTrue(error.getMessage().contains("DB_USERNAME"));
        assertFalse(error.getMessage().contains("private-test-value"));
    }

    @Test
    void missingMysqlPasswordIsReportedWithoutItsValue() {
        var properties = mysql("jdbc:mysql://host:3316/flow_uat", "flow", "${DB_PASSWORD}");
        var error = assertThrows(IllegalStateException.class,
                () -> validate(properties));
        assertTrue(error.getMessage().contains("DB_PASSWORD"));
    }

    @Test
    void validMysqlConfigurationDoesNotRequireOpeningAConnectionToValidate() {
        assertDoesNotThrow(() -> validate(mysql("jdbc:mysql://host:3316/flow_uat", "flow", "private-test-value")));
    }

    @Test
    void h2StillAllowsItsEmbeddedEmptyPassword() {
        assertDoesNotThrow(() -> validate(mysql("jdbc:h2:mem:test;MODE=MySQL", "sa", "")));
    }
}
