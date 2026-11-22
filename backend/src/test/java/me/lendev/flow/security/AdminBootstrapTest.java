package me.lendev.flow.security;

import static org.junit.jupiter.api.Assertions.*;

import me.lendev.flow.common.auth.PasswordHashes;
import me.lendev.flow.common.auth.bootstrap.AdminBootstrap;
import me.lendev.flow.common.auth.bootstrap.AdminBootstrapProperties;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminBootstrapTest extends ApiTestBase {

    @Autowired
    private UserService users;
    @Autowired
    private RoleService roles;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void emptyDatabaseRequiresExplicitPasswordAndStaysEmptyOnFailure() {
        jdbc.update("delete from `user`");
        var bootstrap = new AdminBootstrap(users, roles, new AdminBootstrapProperties());
        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        assertEquals(0, users.count());
    }

    @Test
    void configuredPasswordBootstrapsOnlyOnceWithoutExposingPlaintext() {
        jdbc.update("delete from `user`");
        var properties = new AdminBootstrapProperties();
        properties.setPassword("Bootstrap_123");
        var bootstrap = new AdminBootstrap(users, roles, properties);
        bootstrap.run(null);
        bootstrap.run(null);
        assertEquals(1, users.count());
        var user = users.list().getFirst();
        assertEquals("admin", user.getUsername());
        assertTrue(PasswordHashes.matches("Bootstrap_123", user.getPassword()));
        assertNotEquals("Bootstrap_123", user.getPassword());
        assertNotNull(user.getIdentityKey());
        assertTrue(user.getPasswordChangeRequired());
        assertEquals("SUPER_ADMIN", roles.findRolesByUserId(user.getId()).getFirst().getCode());
    }

    @Test
    void weakBootstrapPasswordFailsBeforeCreatingAnyAccount() {
        jdbc.update("delete from `user`");
        var properties = new AdminBootstrapProperties();
        properties.setPassword("x");
        var bootstrap = new AdminBootstrap(users, roles, properties);
        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        assertEquals(0, users.count());
    }

    @Test
    void existingAccountIsNeverOverwrittenByBootstrapConfiguration() {
        var before = users.getById(1L).getPassword();
        var properties = new AdminBootstrapProperties();
        properties.setPassword("changed_123");
        new AdminBootstrap(users, roles, properties).run(null);
        assertEquals(before, users.getById(1L).getPassword());
    }
}
