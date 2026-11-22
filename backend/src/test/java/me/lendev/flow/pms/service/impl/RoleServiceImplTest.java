package me.lendev.flow.pms.service.impl;

import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.lendev.flow.ServeApplicationTests;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.request.UpdateRoleRequest;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.service.RolePermissionService;
import cn.hutool.core.collection.CollUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class RoleServiceImplTest extends ServeApplicationTests {

    @Autowired
    private RoleServiceImpl roleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Test
    @Transactional
    void updateRole() {
        jdbcTemplate.execute("insert into role (code, name, enable) values ('update_role', 'update_role', 1)");
        Role role = roleService.queryChain().and(ROLE.CODE.eq("update_role")).one();

        UpdateRoleRequest updateRoleRequest = new UpdateRoleRequest();
        updateRoleRequest.setName("abc");
        updateRoleRequest.setEnable(false);

        roleService.updateRole(role.getId(), updateRoleRequest);

        Role role1 = roleService.getById(role.getId());

        assertEquals("abc", role1.getName());
        assertFalse(role1.getEnable());
    }

    @Test
    @Transactional
    void updateRole_1() {
        jdbcTemplate.execute("insert into role (code, name, enable) values ('update_role', 'update_role', 1)");
        Role role = roleService.queryChain().and(ROLE.CODE.eq("update_role")).one();

        UpdateRoleRequest updateRoleRequest = new UpdateRoleRequest();
        updateRoleRequest.setName("abc");

        roleService.updateRole(role.getId(), updateRoleRequest);

        Role role1 = roleService.getById(role.getId());

        assertEquals("abc", role1.getName());
        assertTrue(role1.getEnable());
    }

    @Test
    @Transactional
    void updateRole_all_null() {
        jdbcTemplate.execute("insert into role (code, name, enable) values ('update_role', 'update_role', 1)");
        Role role = roleService.queryChain().and(ROLE.CODE.eq("update_role")).one();

        UpdateRoleRequest updateRoleRequest = new UpdateRoleRequest();

        roleService.updateRole(role.getId(), updateRoleRequest);

        Role role1 = roleService.getById(role.getId());

        assertEquals("update_role", role1.getName());
        assertTrue(role1.getEnable());
    }


    @Test
    @Transactional
    void updateRole_with_permission() {
        jdbcTemplate.execute("insert into role (code, name, enable) values ('update_role', 'update_role', 1)");
        Role role = roleService.queryChain().and(ROLE.CODE.eq("update_role")).one();
        Permission permission = permissionService.queryChain().list().getFirst();

        UpdateRoleRequest updateRoleRequest = new UpdateRoleRequest();
        updateRoleRequest.setPermissionIds(CollUtil.newArrayList(permission.getId()));

        roleService.updateRole(role.getId(), updateRoleRequest);

        Role role1 = roleService.getById(role.getId());

        assertEquals("update_role", role1.getName());
        assertTrue(role1.getEnable());

        assertEquals(1, rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.eq(role1.getId()))
                .and(ROLE_PERMISSION.PERMISSION_ID.eq(permission.getId()))
                .count());

    }

    @Test
    @Transactional
    void updateRole_with_empty_permission() {
        jdbcTemplate.execute("insert into role (code, name, enable) values ('update_role', 'update_role', 1)");
        Role role = roleService.queryChain().and(ROLE.CODE.eq("update_role")).one();

        UpdateRoleRequest updateRoleRequest = new UpdateRoleRequest();
        updateRoleRequest.setPermissionIds(CollUtil.newArrayList());

        roleService.updateRole(role.getId(), updateRoleRequest);
        Role role1 = roleService.getById(role.getId());

        assertEquals("update_role", role1.getName());
        assertTrue(role1.getEnable());

        assertEquals(0, rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.eq(role1.getId()))
                .count());

    }
}