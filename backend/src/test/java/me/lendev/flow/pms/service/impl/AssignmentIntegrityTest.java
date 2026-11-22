package me.lendev.flow.pms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.core.lang.tree.Tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.controller.PermissionController;
import me.lendev.flow.pms.convert.PermissionConverter;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.request.*;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AssignmentIntegrityTest extends ApiTestBase {

    private static final long MISSING = 999999L;
    @Autowired
    RoleService roles;
    @Autowired
    PermissionService permissions;
    @Autowired
    PermissionConverter permissionConverter;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createRoleRejectsMissingPermissionBeforeSavingRole() {
        CreateRoleRequest request = roleRequest("invalid_role", List.of(1L, MISSING));
        assertThrows(BadRequestException.class, () -> roles.createRole(request));
        assertNull(roles.findByCode("invalid_role"));
    }

    @Test
    void updateRoleRejectsMissingPermissionWithoutChangingRoleOrAssignments() {
        UpdateRoleRequest request = new UpdateRoleRequest();
        request.setName("should_not_save");
        request.setPermissionIds(List.of(1L, MISSING));
        assertThrows(BadRequestException.class, () -> roles.updateRole(2L, request));
        assertEquals("质检员", roles.getById(2L).getName());
        assertEquals(11L, permissionCount(2L));
    }

    @Test
    void addPermissionsRejectsMissingTargetWithoutPartialAssignments() {
        AddRolePermissionsRequest request = new AddRolePermissionsRequest();
        request.setId(2L);
        request.setPermissionIds(List.of(6L, MISSING));
        assertThrows(BadRequestException.class, () -> roles.addRolePermissions(request));
        assertEquals(11L, permissionCount(2L));
    }

    @Test
    void addUsersRejectsMissingUserWithoutPartialAssignments() {
        Role role = createRole("assign_users");
        AddRoleUsersRequest request = new AddRoleUsersRequest();
        request.setUserIds(List.of(1L, MISSING));
        Long roleId = role.getId();
        assertThrows(BadRequestException.class, () -> roles.addRoleUsers(roleId, request));
        assertEquals(0L, userCount(role.getId()));
    }

    @Test
    void removeUsersRejectsMissingUserBeforeRemovingExistingAssignment() {
        RemoveRoleUsersRequest request = new RemoveRoleUsersRequest();
        request.setUserIds(List.of(1L, MISSING));
        assertThrows(BadRequestException.class, () -> roles.removeRoleUsers(2L, request));
        assertEquals(1L, userCount(2L));
    }

    @Test
    void serviceRejectsNullAndNonpositiveAssignmentIds() {
        for (List<Long> ids : List.of(Arrays.asList(1L, null), List.of(0L), List.of(-1L))) {
            AddRoleUsersRequest request = new AddRoleUsersRequest();
            request.setUserIds(ids);
            assertThrows(BadRequestException.class, () -> roles.addRoleUsers(2L, request));
        }
        assertEquals(1L, userCount(2L));
    }

    @Test
    void optionalAndEmptyPermissionAssignmentsRemainSupported() {
        roles.createRole(roleRequest("no_permissions", null));
        Role role = roles.findByCode("no_permissions");
        UpdateRoleRequest update = new UpdateRoleRequest();
        roles.updateRole(role.getId(), update);
        update.setPermissionIds(List.of());
        roles.updateRole(role.getId(), update);
        assertEquals(0L, permissionCount(role.getId()));
    }

    @Test
    void duplicateRoleNameUpdateReturnsBusinessErrorWithoutChangingAssignments() {
        UpdateRoleRequest request = new UpdateRoleRequest();
        request.setName("超级管理员");
        request.setPermissionIds(List.of());
        assertThrows(BadRequestException.class, () -> roles.updateRole(2L, request));
        assertEquals("质检员", roles.getById(2L).getName());
        assertEquals(11L, permissionCount(2L));
    }

    @Test
    void permissionParentDeletionIsRejectedWithoutLosingChildrenOrRelations() {
        PermissionController controller = controller();
        assertThrows(BadRequestException.class, () -> controller.remove(3L));
        assertNotNull(permissions.getById(3L));
        assertEquals(3L, permissions.getById(5L).getParentId());
        assertEquals(11L, permissionCount(2L));
    }

    @Test
    void leafPermissionDeletionRemovesRoleRelations() {
        controller().remove(5L);
        assertNull(permissions.getById(5L));
        assertEquals(0L, jdbc.queryForObject("select count(*) from role_permissions_permission where permissionId = 5", Long.class));
    }

    @Test
    void nonexistentPermissionDeletionReturnsBusinessError() {
        PermissionController controller = controller();
        assertThrows(BadRequestException.class, () -> controller.remove(MISSING));
    }

    @Test
    void permissionCreateRejectsMissingParent() {
        CreatePermissionRequest request = permissionRequest("missing_parent", MISSING);
        assertThrows(BadRequestException.class, () -> permissions.create(request));
        assertEquals(0L, jdbc.queryForObject("select count(*) from permission where code = 'missing_parent'", Long.class));
    }

    @Test
    void permissionBatchChecksEveryParentBeforeWriting() {
        List<CreatePermissionRequest> requests = List.of(
                permissionRequest("first_batch", 1L), permissionRequest("invalid_batch", MISSING));
        assertThrows(BadRequestException.class, () -> permissions.createBatch(requests));
        assertEquals(0L, jdbc.queryForObject("select count(*) from permission where code = 'first_batch'", Long.class));
    }

    @Test
    void permissionUpdateRejectsMissingParentAndCycles() {
        PermissionController controller = controller();
        UpdatePermissionRequest request = new UpdatePermissionRequest();
        request.setParentId(MISSING);
        assertThrows(BadRequestException.class, () -> controller.update(3L, request));
        request.setParentId(3L);
        assertThrows(BadRequestException.class, () -> controller.update(3L, request));
        request.setParentId(5L);
        assertThrows(BadRequestException.class, () -> controller.update(3L, request));
        assertNotEquals(5L, permissions.getById(3L).getParentId());
    }

    @Test
    void permissionUpdateRejectsNonexistentTarget() {
        PermissionController controller = controller();
        UpdatePermissionRequest request = new UpdatePermissionRequest();
        assertThrows(BadRequestException.class, () -> controller.update(MISSING, request));
    }

    @Test
    void currentUserMenusExcludeDisabledAncestorsEvenWhenAncestorIsUnassigned() {
        jdbc.update("update permission set enable = 0 where id = 3");
        jdbc.update("delete from role_permissions_permission where roleId = 2 and permissionId = 3");
        for (String code : List.of("SUPER_ADMIN", "ROLE_QA")) {
            List<Long> ids = flatten(roles.findRolePermissionsTree(code));
            assertFalse(ids.contains(3L));
            assertFalse(ids.contains(5L));
        }
        assertTrue(flatten(permissions.findAllTree()).contains(3L));
        assertTrue(flatten(permissions.findAllTree()).contains(5L));
    }

    @Test
    void deletingRoleRemovesAssociationsBeforeRoleRow() {
        Role role = createRole("remove_assigned");
        AddRoleUsersRequest request = new AddRoleUsersRequest();
        request.setUserIds(List.of(1L));
        roles.addRoleUsers(role.getId(), request);
        roles.removeRole(role.getId());
        assertNull(roles.getById(role.getId()));
        assertEquals(0L, userCount(role.getId()));
        assertEquals(0L, permissionCount(role.getId()));
    }

    private PermissionController controller() {
        return new PermissionController(permissions, permissionConverter);
    }

    private Role createRole(String code) {
        roles.createRole(roleRequest(code, List.of(1L)));
        return roles.findByCode(code);
    }

    private CreateRoleRequest roleRequest(String code, List<Long> ids) {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setCode(code);
        request.setName(code);
        request.setPermissionIds(ids);
        return request;
    }

    private CreatePermissionRequest permissionRequest(String code, Long parent) {
        CreatePermissionRequest request = new CreatePermissionRequest();
        request.setName(code);
        request.setCode(code);
        request.setType("MENU");
        request.setParentId(parent);
        return request;
    }

    private long permissionCount(Long roleId) {
        return jdbc.queryForObject("select count(*) from role_permissions_permission where roleId = ?", Long.class, roleId);
    }

    private long userCount(Long roleId) {
        return jdbc.queryForObject("select count(*) from user_roles_role where roleId = ?", Long.class, roleId);
    }

    private List<Long> flatten(List<Tree<Long>> trees) {
        List<Long> ids = new ArrayList<>();
        if (trees != null) {
            for (Tree<Long> tree : trees) {
                ids.add(tree.getId());
                ids.addAll(flatten(tree.getChildren()));
            }
        }
        return ids;
    }
}
