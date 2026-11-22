package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

class PmsAuditApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;

    long role(String token, String code) throws Exception {
        data(postJson("/role", token, map("code", code, "name", code, "enable", true, "permissionIds", List.of(1, 2))));
        return jdbc.queryForObject("SELECT id FROM role WHERE code=?", Long.class, code);
    }

    JsonNode latest(String code) {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1", String.class, code));
    }

    JsonNode snapshot(String code, String side) {
        return latest(code).path("objects").get(0).path(side);
    }

    @Test
    void roleCrudHasActualValuesAndStablePermissionCollections() throws Exception {
        String token = loginAsAdmin();
        long id = role(token, "AUDIT_ROLE");
        assertEquals(Long.toString(id), latest("system.role.create").path("objects").get(0).path("object").path("objectId").asString());
        data(patchJson("/role/" + id, token, map("name", "修改后", "permissionIds", List.of(2, 1, 2))));
        var update = latest("system.role.update").path("objects").get(0);
        assertEquals("AUDIT_ROLE", update.path("beforeData").path("name").path("value").asString());
        assertEquals("修改后", update.path("afterData").path("name").path("value").asString());
        assertEquals(1, update.path("changes").size());
        data(patchJson("/role/" + id, token, map("permissionIds", List.of(1, 2))));
        assertEquals("NO_CHANGE", latest("system.role.update").path("result").asString());
        data(patchJson("/role/" + id, token, map("permissionIds", List.of())));
        assertTrue(snapshot("system.role.update", "afterData").path("permissionIds").path("value").isEmpty());
        data(deleteJson("/role/" + id, token));
        assertTrue(latest("system.role.delete").path("objects").get(0).path("afterData").isNull());
        assertEquals("修改后", latest("system.role.delete").path("objects").get(0).path("object").path("objectName").asString());
    }

    @Test
    void grantsAndMembershipChangesAreAuditedOnceWithActualSets() throws Exception {
        String token = loginAsAdmin();
        long id = role(token, "AUDIT_RELATIONS");
        data(postJson("/role/permissions/add", token, map("id", id, "permissionIds", List.of(3, 3))));
        assertEquals(3, snapshot("system.role.permission.add", "afterData").path("permissionIds").path("value").size());
        data(postJson("/role/permissions/add", token, map("id", id, "permissionIds", List.of(3))));
        assertEquals("NO_CHANGE", latest("system.role.permission.add").path("result").asString());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox", Long.class);
        data(patchJson("/role/users/add/" + id, token, map("userIds", List.of(1, 1))));
        assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox", Long.class));
        assertEquals("1", snapshot("system.role.user.add", "afterData").path("userIds").path("value").get(0).asString());
        data(patchJson("/role/users/remove/" + id, token, map("userIds", List.of(1))));
        assertTrue(snapshot("system.role.user.remove", "afterData").path("userIds").path("value").isEmpty());
    }

    @Test
    void permissionBatchCapturesActualIdsThenUpdateAndDeletePreserveValues() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission/batch", token, List.of(
                map("code", "AUDIT_P1", "name", "资源一", "type", "MENU", "path", "/audit-one"),
                map("code", "AUDIT_P2", "name", "资源二", "type", "BUTTON", "parentId", 1))));
        var batch = latest("system.permission.batch.create");
        assertEquals(2, batch.path("objects").size());
        for (var object : batch.path("objects")) {
            assertTrue(object.path("object").path("objectId").asLong() > 0);
        }
        long id = jdbc.queryForObject("SELECT id FROM permission WHERE code='AUDIT_P1'", Long.class);
        data(patchJson("/permission/" + id, token, map("name", "资源改名", "parentId", 2, "description", "完整描述")));
        assertEquals("资源一", snapshot("system.permission.update", "beforeData").path("name").path("value").asString());
        assertEquals("完整描述", snapshot("system.permission.update", "afterData").path("description").path("value").asString());
        data(deleteJson("/permission/" + id, token));
        assertTrue(latest("system.permission.delete").path("objects").get(0).path("afterData").isNull());
    }

    @Test
    void singlePermissionCreateAndRejectedMutationKeepCorrectFacts() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission", token, map("code", "AUDIT_SINGLE", "name", "单个资源", "type", "MENU")));
        assertEquals("AUDIT_SINGLE", snapshot("system.permission.create", "afterData").path("code").path("value").asString());
        var modules = data(getJson("/audit/options", token)).path("modules").toString();
        assertTrue(modules.contains("ROLE"));
        assertTrue(modules.contains("PERMISSION"));
    }

    @Test
    void permissionSortPersistsDirectMenusAndButtonsAndAuditsTheirOrder() throws Exception {
        String token = loginAsAdmin();
        long parent = createPermission(token, "SORT_PARENT", "MENU", null, 30);
        long first = createPermission(token, "SORT_FIRST", "MENU", parent, 1);
        long second = createPermission(token, "SORT_SECOND", "MENU", parent, 2);
        long button = createPermission(token, "SORT_BUTTON", "BUTTON", parent, 3);
        long nested = createPermission(token, "SORT_NESTED", "MENU", first, 40);

        data(patchJson("/permission/sort", token, map("parentId", parent, "ids", List.of(second, button, first))));

        var menu = data(getJson("/permission/menu/tree", token)).valueStream()
                .filter(row -> row.path("id").asLong() == parent).findFirst().orElseThrow();
        assertEquals(List.of(second, first), menu.path("children").valueStream().map(row -> row.path("id").asLong()).toList());
        assertEquals(2, data(getJson("/permission/button/" + parent, token)).get(0).path("order").asInt());
        assertEquals(40, data(getJson("/permission/" + nested, token)).path("order").asInt());
        var audit = latest("system.permission.sort").path("objects").valueStream()
                .filter(row -> row.path("object").path("objectId").asLong() == first).findFirst().orElseThrow();
        assertEquals(1, audit.path("beforeData").path("order").path("value").asInt());
        assertEquals(3, audit.path("afterData").path("order").path("value").asInt());
    }

    @Test
    void permissionSortWithoutParentReordersOnlyRootMenus() throws Exception {
        String token = loginAsAdmin();
        long root = createPermission(token, "SORT_ROOT", "MENU", null, 90);
        long nested = createPermission(token, "SORT_ROOT_CHILD", "MENU", root, 40);
        createPermission(token, "SORT_ROOT_BUTTON", "BUTTON", null, 80);
        var ids = new ArrayList<>(data(getJson("/permission/menu/tree", token)).valueStream()
                .map(row -> row.path("id").asLong()).toList());
        ids.remove(root);
        ids.addFirst(root);

        data(patchJson("/permission/sort", token, map("ids", ids)));

        var menus = data(getJson("/permission/menu/tree", token));
        assertEquals(ids, menus.valueStream().map(row -> row.path("id").asLong()).toList());
        assertEquals(nested, menus.get(0).path("children").get(0).path("id").asLong());
        assertEquals(40, menus.get(0).path("children").get(0).path("order").asInt());
    }

    @Test
    void permissionSortRejectsIncompleteDuplicateAndOutOfScopeLists() throws Exception {
        String token = loginAsAdmin();
        long parent = createPermission(token, "STALE_PARENT", "MENU", null, 10);
        long menu = createPermission(token, "STALE_MENU", "MENU", parent, 15);
        long button = createPermission(token, "STALE_BUTTON", "BUTTON", parent, 20);
        long nested = createPermission(token, "STALE_NESTED", "MENU", menu, 30);

        for (var ids : List.of(List.of(menu), List.of(button, button), List.of(button, nested))) {
            body(patchJson("/permission/sort", token, map("parentId", parent, "ids", ids)), 400);
        }
        assertEquals(15, data(getJson("/permission/" + menu, token)).path("order").asInt());
        assertEquals(20, data(getJson("/permission/" + button, token)).path("order").asInt());
    }

    private long createPermission(String token, String code, String type, Long parentId, int order) throws Exception {
        data(postJson("/permission", token, map("code", code, "name", code, "type", type, "parentId", parentId, "order", order)));
        return jdbc.queryForObject("SELECT id FROM permission WHERE code=?", Long.class, code);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectedMutationRecordsTargetAfterItsTransactionRollsBack() throws Exception {
        String token = loginAsAdmin();
        body(patchJson("/role/99999999", token, map("name", "missing")), 400);
        assertEquals("FAILURE", latest("system.role.update").path("result").asString());
        assertEquals("99999999", latest("system.role.update").path("objects").get(0).path("object").path("objectId").asString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initialAuditFailureRollsBackPermissionAndRoleMutations() throws Exception {
        String token = loginAsAdmin();
        long id = role(token, "AUDIT_FAILURE");
        long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM sys_audit_outbox", Long.class);
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_pms_audit CHECK(id <= " + existing + ")");
        try {
            body(patchJson("/role/" + id, token, map("name", "必须回滚")), 503);
            assertEquals("AUDIT_FAILURE", jdbc.queryForObject("SELECT name FROM role WHERE id=?", String.class, id));
            body(postJson("/permission", token, map("code", "AUDIT_ROLLBACK", "name", "必须回滚", "type", "MENU")), 503);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM permission WHERE code='AUDIT_ROLLBACK'", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_pms_audit");
            jdbc.update("DELETE FROM role_permissions_permission WHERE roleId=?", id);
            jdbc.update("DELETE FROM role WHERE id=?", id);
        }
    }
}
