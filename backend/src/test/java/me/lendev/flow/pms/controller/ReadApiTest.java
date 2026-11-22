package me.lendev.flow.pms.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;

import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;

/**
 * 查询类接口测试
 */
class ReadApiTest extends ApiTestBase {

    @Test
    void findAllRoles() throws Exception {
        String token = loginAsAdmin();
        JsonNode data = data(getJson("/role", token));
        assertEquals(2, data.size());
        assertEquals("SUPER_ADMIN", data.get(0).get("code").asString());

        JsonNode enabled = data(getJson("/role", token, "enable", true));
        assertEquals(2, enabled.size());
    }

    @Test
    void findRolePage() throws Exception {
        String token = loginAsAdmin();
        JsonNode data = data(getJson("/role/page", token, "pageNo", 1, "pageSize", 10));
        assertEquals(2, data.get("total").asLong());
        JsonNode first = data.get("pageData").get(0);
        assertEquals("SUPER_ADMIN", first.get("code").asString());
        assertTrue(first.get("permissionIds").isEmpty());
    }

    @Test
    void findRoleById() throws Exception {
        String token = loginAsAdmin();
        assertEquals("SUPER_ADMIN", data(getJson("/role/1", token)).get("code").asString());
    }

    @Test
    void findRolePermissions() throws Exception {
        String token = loginAsAdmin();
        JsonNode data = data(getJson("/role/permissions", token, "id", 2));
        assertEquals(11, data.size());
        assertNotNull(data.get(0).get("name").asString());
    }

    @Test
    void findRolePermissionsTree() throws Exception {
        String token = loginAsAdmin();
        JsonNode adminTree = data(getJson("/role/permissions/tree", token));
        assertTrue(adminTree.size() > 0);

        String qaToken = data(postJson("/auth/current-role/switch/ROLE_QA", token, Map.of()))
                .get("accessToken").asString();
        JsonNode qaTree = data(getJson("/role/permissions/tree", qaToken));
        assertTrue(qaTree.size() > 0);
    }

    @Test
    void findAllPermissions() throws Exception {
        String token = loginAsAdmin();
        JsonNode menu = data(getJson("/permission", token));
        assertTrue(menu.size() > 0);
        assertEquals("MENU", menu.get(0).get("type").asString());
    }

    @Test
    void findPermissionTrees() throws Exception {
        String token = loginAsAdmin();
        assertTrue(data(getJson("/permission/tree", token)).size() > 0);
        assertTrue(data(getJson("/permission/menu/tree", token)).size() > 0);
    }

    @Test
    void findPermissionById() throws Exception {
        String token = loginAsAdmin();
        JsonNode data = data(getJson("/permission/1", token));
        assertEquals("Resource_Mgt", data.get("code").asString());
    }

    @Test
    void findButtonsAndValidateMenuPath() throws Exception {
        String token = loginAsAdmin();
        assertTrue(data(getJson("/permission/button/2", token)).isArray());
        assertTrue(data(getJson("/permission/menu/validate", token, "path", "/pms/resource")).asBoolean());
        assertFalse(data(getJson("/permission/menu/validate", token, "path", "/nope")).asBoolean());
    }

    @Test
    void findUserPage() throws Exception {
        String token = loginAsAdmin();
        JsonNode data = data(getJson("/user", token, "pageNo", 1, "pageSize", 10));
        assertEquals(1, data.get("total").asLong());
        JsonNode first = data.get("pageData").get(0);
        assertEquals(ADMIN, first.get("username").asString());
        assertEquals(2, first.get("roles").size());

        assertTrue(data(getJson("/user", token,
                "username", ADMIN, "enable", true, "gender", 1, "pageNo", 1, "pageSize", 5)).get("total").asLong() >= 0);
        assertEquals(0, data(getJson("/user", token, "username", "nobody")).get("total").asLong());
    }

}
