package me.lendev.flow.pms.controller;

import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.service.RolePermissionService;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserRoleService;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 角色写操作接口测试
 */
class RoleWriteApiTest extends ApiTestBase {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private UserRoleService userRoleService;

    @Autowired
    private UserService userService;

    @Test
    void createRole() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/role", token, map(
                "code", "ROLE_NEW",
                "name", "新角色",
                "enable", true,
                "permissionIds", List.of(1, 2))));

        Role role = roleService.findByCode("ROLE_NEW");
        assertEquals("新角色", role.getName());
        assertEquals(2, rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.eq(role.getId())).count());
    }

    @Test
    void createDuplicatedRole() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(postJson("/role", token, map("code", "SUPER_ADMIN", "name", "x")), 400);
        assertEquals(400, body.get("code").asInt());
        assertEquals("角色已存在（角色名和角色编码不能重复）", body.get("message").asString());
    }

    @Test
    void createRoleWithoutCodeIsInvalid() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = code(postJson("/role", token, Map.of()), 400);
        assertTrue(body.get("message").asString().contains("不能为空"));
    }

    @Test
    void updateRole() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/role", token, map("code", "ROLE_UPD", "name", "待更新", "enable", true,
                "permissionIds", List.of())));
        Role role = roleService.findByCode("ROLE_UPD");

        data(patchJson("/role/" + role.getId(), token, map(
                "name", "已更新", "enable", false, "permissionIds", List.of(3))));

        Role updated = roleService.getById(role.getId());
        assertEquals("已更新", updated.getName());
        assertEquals(false, updated.getEnable());
        assertEquals(1, rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.eq(role.getId())).count());
    }

    @Test
    void updateSuperAdminRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(patchJson("/role/1", token, map("name", "x")), 400);
        assertEquals("不允许修改超级管理员", body.get("message").asString());
    }

    @Test
    void updateMissingRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(patchJson("/role/999999", token, map("name", "x")), 400);
        assertEquals("角色不存在或者已删除", body.get("message").asString());
    }

    @Test
    void removeRole() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/role", token, map("code", "ROLE_DEL", "name", "待删除",
                "permissionIds", List.of(1))));
        Role role = roleService.findByCode("ROLE_DEL");

        data(deleteJson("/role/" + role.getId(), token));

        assertNull(roleService.getById(role.getId()));
        assertEquals(0, rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.eq(role.getId())).count());
        assertEquals(0, userRoleService.queryChain().and(USER_ROLE.ROLE_ID.eq(role.getId())).count());
    }

    @Test
    void removeSuperAdminRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(deleteJson("/role/1", token), 400);
        assertEquals("不允许修改超级管理员", body.get("message").asString());
    }

    @Test
    void removeMissingRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(deleteJson("/role/999999", token), 400);
        assertEquals("角色不存在或者已删除", body.get("message").asString());
    }

    @Test
    void addRolePermissions() throws Exception {
        String token = loginAsAdmin();
        long before = rolePermissionService.queryChain().and(ROLE_PERMISSION.ROLE_ID.eq(2L)).count();

        data(postJson("/role/permissions/add", token, map("id", 2, "permissionIds", List.of(1, 6))));

        assertEquals(before + 1, rolePermissionService.queryChain().and(ROLE_PERMISSION.ROLE_ID.eq(2L)).count());
        assertEquals(1, rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.eq(2L))
                .and(ROLE_PERMISSION.PERMISSION_ID.eq(6L)).count());
    }

    @Test
    void addPermissionsToSuperAdminIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(postJson("/role/permissions/add", token,
                map("id", 1, "permissionIds", List.of(6))), 400);
        assertEquals("无需给超级管理员授权", body.get("message").asString());
    }

    @Test
    void addPermissionsToMissingRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(postJson("/role/permissions/add", token,
                map("id", 999999, "permissionIds", List.of(6))), 400);
        assertEquals("角色不存在或者已删除", body.get("message").asString());
    }

    @Test
    void addRoleUsers() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/user", token, map("username", "role_user", "password", "pw123456")));
        User user = userService.queryChain().and(USER.USERNAME.eq("role_user")).one();

        data(patchJson("/role/users/add/2", token, map("userIds", List.of(user.getId(), 1))));

        assertEquals(1, userRoleService.queryChain()
                .and(USER_ROLE.ROLE_ID.eq(2L))
                .and(USER_ROLE.USER_ID.eq(user.getId())).count());
        assertEquals(1, userRoleService.queryChain()
                .and(USER_ROLE.ROLE_ID.eq(2L))
                .and(USER_ROLE.USER_ID.eq(1L)).count());
    }

    @Test
    void addUsersToMissingRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(patchJson("/role/users/add/999999", token, map("userIds", List.of(1))), 400);
        assertEquals("角色不存在或者已删除", body.get("message").asString());
    }

    @Test
    void removeRoleUsers() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/role/users/remove/2", token, map("userIds", List.of(1))));
        assertEquals(0, userRoleService.queryChain()
                .and(USER_ROLE.ROLE_ID.eq(2L))
                .and(USER_ROLE.USER_ID.eq(1L)).count());
    }

    @Test
    void removeUsersFromMissingRoleIsRejected() throws Exception {
        String token = loginAsAdmin();
        JsonNode body = body(patchJson("/role/users/remove/999999", token, map("userIds", List.of(1))), 400);
        assertEquals("角色不存在或者已删除", body.get("message").asString());
    }

    @Test
    void missingUserIdsCannotRemoveEveryRoleMember() throws Exception {
        String token = loginAsAdmin();
        body(patchJson("/role/users/remove/2", token, map()), 400);
        assertEquals(1, userRoleService.queryChain().and(USER_ROLE.ROLE_ID.eq(2L)).count());
    }

    @Test
    void emptyUserIdsLeaveRoleMembersUnchanged() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/role/users/remove/2", token, map("userIds", List.of())));
        assertEquals(1, userRoleService.queryChain().and(USER_ROLE.ROLE_ID.eq(2L)).count());
    }

}
