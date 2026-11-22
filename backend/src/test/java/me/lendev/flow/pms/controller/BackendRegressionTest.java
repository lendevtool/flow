package me.lendev.flow.pms.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.lendev.flow.common.request.PageRequest;
import me.lendev.flow.pms.domain.request.*;
import me.lendev.flow.pms.service.*;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BackendRegressionTest extends ApiTestBase {

    @Autowired
    private UserService users;
    @Autowired
    private RoleService roles;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ordinaryRoleCannotWriteAdministrativeData() throws Exception {
        String token = data(postJson("/auth/current-role/switch/ROLE_QA", loginAsAdmin(), Map.of()))
                .get("accessToken").asString();
        code(patchJson("/user/1", token, map("roleIds", List.of(1))), 11003);
        code(postJson("/user/roles/add/1", token, map("roleIds", List.of(1))), 11003);
        code(postJson("/auth/register", token, map("username", "escalate", "password", "123456", "roleIds", List.of(1))), 11003);
        code(postJson("/permission", token, map("name", "x", "code", "x", "type", "MENU")), 11003);
        code(postJson("/permission/batch", token, List.of()), 11003);
        code(patchJson("/permission/1", token, map("name", "changed")), 11003);
        code(deleteJson("/permission/1", token), 11003);
    }

    @Test
    void profileUpdateUsesAuthenticatedUserInsteadOfBodyId() throws Exception {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername("other_user");
        request.setPassword("Fixture1234!");
        users.register(request);
        Long otherProfileId = jdbc.queryForObject(
                "select p.id from profile p join `user` u on p.userId=u.id where u.username='other_user'", Long.class);
        data(patchJson("/user/profile/1", loginAsAdmin(), map("id", otherProfileId, "nickName", "safe")));
        assertEquals("safe", profiles.findByUserId(1L).getNickName());
        assertNull(profiles.getById(otherProfileId).getNickName());
    }

    @Test
    void roleFiltersMustBothMatch() {
        RolePageRequest request = new RolePageRequest();
        request.setName("missing_role");
        request.setEnable(true);
        assertEquals(0L, roles.queryPage(request).getTotal());
        request.setName("超级管理员");
        assertEquals(List.of(1L), roles.queryPage(request).getPageData().stream().map(r -> r.getId()).toList());
    }

    @Test
    void roleCanBeCreatedWithoutPermissions() {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setCode("EMPTY_ROLE");
        request.setName("empty");
        roles.createRole(request);
        assertTrue(roles.findRolePermissions(roles.findByCode("EMPTY_ROLE").getId()).isEmpty());
    }

    @Test
    void repeatedAssignmentsAcceptImmutableListsAndDoNotDuplicate() {
        AddRoleUsersRequest usersRequest = new AddRoleUsersRequest();
        usersRequest.setUserIds(List.of(1L, 1L));
        roles.addRoleUsers(2L, usersRequest);
        assertEquals(List.of(1L, 1L), usersRequest.getUserIds());
        assertEquals(1, jdbc.queryForObject("select count(*) from user_roles_role where userId=1 and roleId=2", Integer.class));

        AddRolePermissionsRequest permissions = new AddRolePermissionsRequest();
        permissions.setId(2L);
        permissions.setPermissionIds(List.of(1L, 1L, 15L, 15L));
        roles.addRolePermissions(permissions);
        roles.addRolePermissions(permissions);
        assertEquals(1, jdbc.queryForObject("select count(*) from role_permissions_permission where roleId=2 and permissionId=15", Integer.class));
    }

    @Test
    void emptyUserRolesClearsAssignmentsAndDeletionCleansRelations() {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername("clean_user");
        request.setPassword("Fixture1234!");
        request.setRoleIds(List.of(2L, 2L));
        users.register(request);
        Long id = jdbc.queryForObject("select id from `user` where username='clean_user'", Long.class);
        AddUserRolesRequest assignments = new AddUserRolesRequest();
        assignments.setRoleIds(List.of());
        users.addRoles(id, assignments);
        assertEquals(0, jdbc.queryForObject("select count(*) from user_roles_role where userId=?", Integer.class, id));
        assignments.setRoleIds(List.of(2L, 2L));
        users.addRoles(id, assignments);
        users.removeUser(id);
        assertEquals(0, jdbc.queryForObject("select count(*) from user_roles_role where userId=?", Integer.class, id));
        assertNull(profiles.findByUserId(id));
    }

    @Test
    void disabledAccountAndRoleCannotGetNewTokens() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("update role set enable=0 where id=2");
        code(postJson("/auth/current-role/switch/ROLE_QA", token, Map.of()), 11005);
        jdbc.update("update `user` set enable=0 where id=1");
        var captcha = captchaService.create();
        code(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD,
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())), 10002);
    }

    @Test
    void refreshRejectsDisabledAccountAndRemovedCurrentRole() throws Exception {
        String admin = loginAsAdmin();
        String qa = data(postJson("/auth/current-role/switch/ROLE_QA", admin, Map.of()))
                .get("accessToken").asString();
        jdbc.update("delete from user_roles_role where userId=1 and roleId=2");
        code(getJson("/auth/refresh/token", qa), 11005);
        jdbc.update("update `user` set enable=0 where id=1");
        code(getJson("/auth/refresh/token", admin), 10002);
    }

    @Test
    void batchPageAssemblyIncludesUsersAndRolesWithNoAssociations() {
        RegisterUserRequest register = new RegisterUserRequest();
        register.setUsername("page_user");
        register.setPassword("Fixture1234!");
        users.register(register);
        UserPageRequest userPage = new UserPageRequest();
        userPage.setUsername("page_user");
        assertTrue(users.queryPage(userPage).getPageData().getFirst().getRoles().isEmpty());
        userPage.setUsername("missing_user");
        assertTrue(users.queryPage(userPage).getPageData().isEmpty());
        CreateRoleRequest role = new CreateRoleRequest();
        role.setCode("PAGE_ROLE");
        role.setName("page_role");
        role.setPermissionIds(List.of(1L, 1L, 2L));
        roles.createRole(role);
        RolePageRequest rolePage = new RolePageRequest();
        rolePage.setName("page_role");
        assertEquals(2, roles.queryPage(rolePage).getPageData().getFirst().getPermissionIds().size());
    }

    @Test
    void invalidCredentialsAreValidatedBeforeServiceCall() throws Exception {
        code(postJson("/auth/login", Map.of()), 400);
        code(postJson("/user", loginAsAdmin(), map("username", "x")), 400);
    }

    @Test
    void permissionCreateKeepsKeepAliveFlag() throws Exception {
        data(postJson("/permission", loginAsAdmin(), map("name", "keep", "code", "KEEP_REGRESSION", "type", "MENU", "keepalive", true)));
        assertEquals(1, jdbc.queryForObject("select keepAlive from permission where code='KEEP_REGRESSION'", Integer.class));
    }

    @Test
    void paginationHandlesNullAndNegativeValues() {
        PageRequest request = new PageRequest();
        request.setPageNo(null);
        request.setPageSize(null);
        assertEquals(1, request.toPage().getPageNumber());
        assertEquals(10, request.toPage().getPageSize());
        request.setPageNo(-1);
        request.setPageSize(-1);
        assertEquals(1, request.toPage().getPageNumber());
        assertEquals(1, request.toPage().getPageSize());
        request.setPageSize(1000);
        assertEquals(500, request.toPage().getPageSize());
    }
}
