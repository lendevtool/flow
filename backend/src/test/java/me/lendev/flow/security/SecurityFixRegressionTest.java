package me.lendev.flow.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SecurityFixRegressionTest extends ApiTestBase {

    @Autowired
    private UserService users;
    @Autowired
    private JdbcTemplate jdbc;

    private Long register(String username) {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername(username);
        request.setPassword("Fixture1234!");
        request.setRoleIds(List.of(2L));
        users.register(request);
        return jdbc.queryForObject("select id from `user` where username=?", Long.class, username);
    }

    @Test
    void accountWithoutRolesCannotEnumeratePrivateProfiles() throws Exception {
        register("secure_reader");
        jdbc.update("delete from user_roles_role where userId=(select id from `user` where username='secure_reader')");
        code(getJson("/user", login("secure_reader", "Fixture1234!")), 11003);
    }

    @Test
    void ordinaryRoleCannotReadManagementUserList() throws Exception {
        register("secure_regular");
        code(getJson("/user", login("secure_regular", "Fixture1234!")), 11003);
        data(getJson("/user", loginAsAdmin()));
    }

    @Test
    void passwordChangesRejectWeakAndOverlongValues() throws Exception {
        String token = loginAsAdmin();
        body(postJson("/auth/password", token, map("oldPassword", ADMIN_PASSWORD, "newPassword", "x")), 400);
        body(postJson("/auth/password", token, map("oldPassword", ADMIN_PASSWORD, "newPassword", "a".repeat(21))), 400);
        data(getJson("/user/detail", token));
    }

    @Test
    void registeredUserMustReferenceExistingRolesBeforeAnyInsert() throws Exception {
        String token = loginAsAdmin();
        body(postJson("/user", token, map("username", "bad_role_user", "password", "Fixture1234!", "roleIds", List.of(2L, 999999L))), 400);
        assertEquals(0, jdbc.queryForObject("select count(*) from `user` where username='bad_role_user'", Integer.class));
    }

    @Test
    void replacingRolesWithMissingTargetPreservesPreviousAssignments() throws Exception {
        Long id = register("secure_target");
        body(postJson("/user/roles/add/" + id, loginAsAdmin(), map("roleIds", List.of(999999))), 400);
        assertEquals(List.of(2L), jdbc.queryForList("select roleId from user_roles_role where userId=?", Long.class, id));
    }

    @Test
    void oldTokenCannotImpersonateRecreatedUserWithSameNumericId() throws Exception {
        Long id = register("secure_old");
        String token = login("secure_old", "Fixture1234!");
        jdbc.update("delete from profile where userId=?", id);
        jdbc.update("delete from user_roles_role where userId=?", id);
        jdbc.update("delete from `user` where id=?", id);
        jdbc.update("insert into `user`(id,username,password,enable) values (?,?,?,1)", id, "secure_new", "$2a$10$FsAafxTTVVGXfIkJqvaiV.1vPfq4V9HW298McPldJgO829PR52a56");
        jdbc.update("insert into user_roles_role(userId,roleId) values (?,2)", id);
        body(getJson("/user/detail", token), 401);
    }

    @Test
    void duplicatePermissionCodeReturnsStableClientError() throws Exception {
        var response = body(postJson("/permission", loginAsAdmin(),
                map("name", "duplicate", "code", "RoleMgt", "type", "MENU")), 400);
        assertEquals(400, response.get("code").asInt());
    }

}
