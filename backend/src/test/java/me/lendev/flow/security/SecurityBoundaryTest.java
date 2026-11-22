package me.lendev.flow.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.pms.domain.request.CreateRoleRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

class SecurityBoundaryTest extends ApiTestBase {

    @Autowired
    private RoleService roles;
    @Autowired
    private UserService users;
    @Autowired
    private JdbcTemplate jdbc;

    private Long createRoleManager(List<Long> permissions) {
        var role = new CreateRoleRequest();
        role.setCode(RoleType.ROLE_PMS);
        role.setName("security_role_manager");
        role.setPermissionIds(permissions);
        roles.createRole(role);
        Long roleId = roles.findByCode(RoleType.ROLE_PMS).getId();
        var user = new RegisterUserRequest();
        user.setUsername("security_manager");
        user.setPassword("Fixture1234!");
        user.setRoleIds(List.of(roleId));
        users.register(user);
        return roleId;
    }

    @Test
    void alternateRoleUpdateCannotBypassSuperAdminPermissionAssignment() throws Exception {
        Long roleId = createRoleManager(List.of());
        String token = login("security_manager", "Fixture1234!");
        code(postJson("/role/permissions/add", token, map("id", roleId, "permissionIds", List.of(13))), 11003);
        code(patchJson("/role/" + roleId, token, map("permissionIds", List.of(13))), 11003);
        assertEquals(0, jdbc.queryForObject("select count(*) from role_permissions_permission where roleId=?", Integer.class, roleId));
    }

    @Test
    void roleManagerCannotClearPermissionsThroughUpdateEither() throws Exception {
        Long roleId = createRoleManager(List.of(3L));
        code(patchJson("/role/" + roleId, login("security_manager", "Fixture1234!"), map("permissionIds", List.of())), 11003);
        assertEquals(List.of(3L), jdbc.queryForList("select permissionId from role_permissions_permission where roleId=?", Long.class, roleId));
    }

    @Test
    void roleManagerCanStillChangeRoleNameWithoutChangingPermissions() throws Exception {
        Long roleId = createRoleManager(List.of(3L));
        data(patchJson("/role/" + roleId, login("security_manager", "Fixture1234!"), map("name", "renamed")));
        assertEquals("renamed", roles.getById(roleId).getName());
        assertEquals(List.of(3L), jdbc.queryForList("select permissionId from role_permissions_permission where roleId=?", Long.class, roleId));
    }

    @ParameterizedTest
    @CsvSource({"admin,false", "unknown_account,false", "admin,true"})
    void missingCaptchaDoesNotDiscloseWhetherAccountExistsOrIsEnabled(String username, boolean disabled) throws Exception {
        if (disabled) {
            jdbc.update("update `user` set enable=0 where id=1");
        }
        code(postJson("/auth/login", map("username", username, "password", "Fixture1234!")), 10003);
    }

    @Test
    void invalidUsernameStillConsumesItsValidChallenge() throws Exception {
        var captcha = captchaService.create();
        code(postJson("/auth/login", map("username", "unknown_account", "password", "Fixture1234!",
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())), 10002);
        code(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD,
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())), 10003);
    }

    @Test
    void authenticationInputsAreBoundedBeforeRedisOrDatabaseWork() throws Exception {
        code(postJson("/auth/login", map("username", "u".repeat(51), "password", "Fixture1234!")), 400);
        code(postJson("/auth/login", map("username", ADMIN, "password", "p".repeat(73))), 400);
        code(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD, "captchaKey", "k".repeat(65))), 400);
        code(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD, "captcha", "c".repeat(5))), 400);
    }

    @Test
    void sensitiveSuccessAndUnauthorizedResponsesCannotBeStoredByCaches() throws Exception {
        String token = loginAsAdmin();
        assertEquals("no-store", getJson("/user/detail", token).andReturn().getResponse().getHeader("Cache-Control"));
        assertEquals("no-store", getJson("/auth/refresh/token", token).andReturn().getResponse().getHeader("Cache-Control"));
        var response = mockMvc.perform(get("/user/detail")).andReturn().getResponse();
        assertEquals(401, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/documents", "/institutions", "/field-definition/metadata", "/trading-calendar/markets",
            "/department", "/department/members", "/notification-groups", "/notification-groups/candidates/users",
            "/document-generation/templates", "/document-generation/admin/templates"})
    void businessResponsesCannotBeStoredByCaches(String path) throws Exception {
        var authorized = getJson(path, loginAsAdmin());
        data(authorized);
        assertEquals("no-store", authorized.andReturn().getResponse().getHeader(HttpHeaders.CACHE_CONTROL));

        var unauthorized = mockMvc.perform(get(path)).andReturn().getResponse();
        assertEquals(401, unauthorized.getStatus());
        assertEquals("no-store", unauthorized.getHeader(HttpHeaders.CACHE_CONTROL));
    }
}
