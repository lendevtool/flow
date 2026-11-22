package me.lendev.flow.pms.controller;

import static me.lendev.flow.pms.domain.entity.table.PermissionTableDef.PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 权限写操作接口测试
 */
class PermissionWriteApiTest extends ApiTestBase {

    @Autowired
    private PermissionService permissionService;

    @Test
    void createPermission() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission", token, map(
                "name", "新权限",
                "code", "PERM_NEW",
                "type", "MENU",
                "parentId", 2,
                "path", "/new",
                "icon", "i-fe:new",
                "show", true,
                "enable", true,
                "order", 3)));

        Permission saved = permissionService.queryChain().and(PERMISSION.CODE.eq("PERM_NEW")).one();
        assertEquals("新权限", saved.getName());
        assertEquals("MENU", saved.getType());
        assertEquals(2L, saved.getParentId());
        assertEquals("/new", saved.getPath());
        assertEquals("i-fe:new", saved.getIcon());
        assertTrue(saved.getShow());
        assertTrue(saved.getEnable());
        assertEquals(Integer.valueOf(3), saved.getOrder());
    }

    @Test
    void createPermissionsInBatch() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission/batch", token, List.of(
                map("name", "批量一", "code", "PERM_B1", "type", "BUTTON", "parentId", 1, "show", true, "enable", true),
                map("name", "批量二", "code", "PERM_B2", "type", "BUTTON", "parentId", 1, "show", true, "enable", true))));

        assertEquals(2, permissionService.queryChain().and(PERMISSION.CODE.in("PERM_B1", "PERM_B2")).count());
    }

    @Test
    void updatePermission() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/permission/1", token, map(
                "name", "资源管理-改",
                "keepAlive", true,
                "description", "desc",
                "order", 5)));

        Permission updated = permissionService.getById(1L);
        assertEquals("资源管理-改", updated.getName());
        assertTrue(updated.getKeepAlive());
        assertEquals("desc", updated.getDescription());
        assertEquals(Integer.valueOf(5), updated.getOrder());
    }

    @Test
    void removePermission() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission", token, map("name", "待删除", "code", "PERM_DEL", "type", "MENU")));
        Permission saved = permissionService.queryChain().and(PERMISSION.CODE.eq("PERM_DEL")).one();
        assertNotNull(saved.getId());

        data(deleteJson("/permission/" + saved.getId(), token));
        assertNull(permissionService.getById(saved.getId()));
    }

    @Test
    void findButtonsOfParent() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission", token,
                map("name", "按钮", "code", "PERM_BTN", "type", "BUTTON", "parentId", 2, "order", 1)));

        List<Permission> buttons = permissionService.findButton(2L);
        assertTrue(buttons.stream().anyMatch(item -> "PERM_BTN".equals(item.getCode())));
    }

    @Test
    void validateMenuPath() {
        assertTrue(permissionService.validateMenuPath("/pms/resource"));
        assertFalse(permissionService.validateMenuPath("/not-exists"));
        assertEquals(11, permissionService.findByRoleId(2L).size());
        assertTrue(permissionService.findAllMenu().size() > 0);
        assertTrue(permissionService.findAllMenuTree().size() > 0);
        assertTrue(permissionService.findAllTree().size() > 0);
    }

}
