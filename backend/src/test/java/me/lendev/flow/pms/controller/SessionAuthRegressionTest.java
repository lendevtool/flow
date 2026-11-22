package me.lendev.flow.pms.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话鉴权回归测试：验证无状态 Token 无法保障的鉴权契约 （登出后 Token 立即失效、多次登录互不干扰且各自持有当前角色）。
 */
class SessionAuthRegressionTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void logoutRevokesTokenOnNextRequest() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/auth/logout", token, Map.of()));
        body(getJson("/user/detail", token), 401);
    }

    @Test
    void loginsHaveIndependentTokensAndCurrentRoles() throws Exception {
        String first = loginAsAdmin();
        String second = loginAsAdmin();
        assertNotEquals(first, second);
        assertFalse(first.contains("."));
        String switched = data(postJson("/auth/current-role/switch/ROLE_QA", first, Map.of()))
                .get("accessToken").asString();
        assertEquals(first, switched);
        assertEquals("ROLE_QA", data(getJson("/user/detail", first)).get("currentRole").get("code").asString());
        assertEquals("SUPER_ADMIN", data(getJson("/user/detail", second)).get("currentRole").get("code").asString());
        data(postJson("/auth/logout", first, Map.of()));
        body(getJson("/user/detail", first), 401);
        data(getJson("/user/detail", second));
    }

    @Test
    void refreshKeepsCurrentTokenAndRole() throws Exception {
        String token = loginAsAdmin();
        String switched = data(postJson("/auth/current-role/switch/ROLE_QA", token, Map.of()))
                .get("accessToken").asString();
        assertEquals(switched, data(getJson("/auth/refresh/token", switched)).get("accessToken").asString());
        assertEquals("ROLE_QA", data(getJson("/user/detail", switched)).get("currentRole").get("code").asString());
    }

    @Test
    void removedAndDisabledRolesCannotAuthorizeExistingTokens() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("delete from user_roles_role where userId=1 and roleId=1");
        code(postJson("/role", token, map("code", "REVOKED", "name", "revoked")), 11003);
        code(getJson("/role/permissions/tree", token), 11005);
        jdbc.update("insert into user_roles_role(userId, roleId) values (1,1)");
        jdbc.update("update role set enable=0 where id=1");
        code(postJson("/role", token, map("code", "DISABLED", "name", "disabled")), 11003);
    }

    @Test
    void disabledAccountCannotReadWithExistingToken() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("update `user` set enable=0 where id=1");
        code(getJson("/user/detail", token), 10002);
    }

    @Test
    void resetPasswordRevokesEveryLoginOfTargetAccount() throws Exception {
        String admin = loginAsAdmin();
        var created = data(postJson("/user", admin, map("username", "session_user", "roleIds", List.of(2))));
        Long id = jdbc.queryForObject("select id from `user` where username='session_user'", Long.class);
        String temporary = created.path("temporaryPassword").asString();
        String restricted = login("session_user", temporary);
        code(getJson("/user/detail", restricted), 10005);
        assertEquals(12, data(getJson("/auth/password/rules", restricted)).path("minLength").asInt());
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", "StartTest123!")));
        String first = login("session_user", "StartTest123!");
        String second = login("session_user", "StartTest123!");
        var reset = data(patchJson("/user/password/reset/" + id, admin, null));
        body(getJson("/user/detail", first), 401);
        body(getJson("/user/detail", second), 401);
        code(getJson("/user/detail", login("session_user", reset.path("temporaryPassword").asString())), 10005);
    }

    @Test
    void changePasswordRevokesEveryLogin() throws Exception {
        String first = loginAsAdmin();
        String second = loginAsAdmin();
        data(postJson("/auth/password", first, map("oldPassword", ADMIN_PASSWORD, "newPassword", "NewTest1234!")));
        body(getJson("/user/detail", first), 401);
        body(getJson("/user/detail", second), 401);
        data(getJson("/user/detail", login(ADMIN, "NewTest1234!")));
    }

    @Test
    void batchPermissionElementsAreValidatedBeforePersistence() throws Exception {
        code(postJson("/permission/batch", loginAsAdmin(), List.of(map("name", "missing code and type"))), 400);
        assertEquals(0, jdbc.queryForObject("select count(*) from permission where name='missing code and type'", Integer.class));
    }

    @Test
    void invalidAssignmentListsAreRejectedBeforePersistence() throws Exception {
        String token = loginAsAdmin();
        code(postJson("/user/roles/add/1", token, map("roleIds", java.util.Arrays.asList((Long) null))), 400);
        code(postJson("/role/permissions/add", token, map("permissionIds", List.of(1))), 400);
        code(patchJson("/role/users/add/2", token, map("userIds", List.of(-1))), 400);
    }

}
