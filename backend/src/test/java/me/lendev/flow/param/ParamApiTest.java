package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ParamApiTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void rejectsMarketConfigurationThatCannotBeImportedOrDisplayed() throws Exception {
        String token = loginAsAdmin();
        long groupId = jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = 'TRADING_MARKET'", Long.class);
        String path = "/param/groups/" + groupId + "/items";
        body(postJson(path, token, map("code", "badCode", "name", "测试市场",
                "value1", "Asia/Shanghai", "value2", "cn")), 400);
        body(postJson(path, token, map("code", "BAD_ZONE", "name", "测试市场",
                "value1", "Not/AZone", "value2", "cn")), 400);
        body(postJson(path, token, map("code", "BAD_ICON", "name", "测试市场",
                "value1", "Asia/Shanghai", "value2", "https://example.invalid/icon")), 400);
    }

    @Test
    void marketWithCalendarRecordsCanBeDisabledButCannotBeDeleted() throws Exception {
        String token = loginAsAdmin();
        long groupId = jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = 'TRADING_MARKET'", Long.class);
        var market = item(token, groupId, "TESTMARKET", "value1", "Asia/Shanghai", "value2", "cn");
        long id = market.get("id").asLong();
        jdbc.update("""
                INSERT INTO sys_trading_calendar_day (marketItemId, calendarDate, calendarDate8, isTradingDay, source)
                VALUES (?, DATE '2099-01-01', '20990101', 1, 'INIT')
                """, id);
        String path = "/param/groups/" + groupId + "/items/" + id;
        body(deleteJson(path, token), 400);
        assertFalse(data(patchJson(path, token, map("code", "TESTMARKET", "name", "测试市场",
                "value1", "Asia/Shanghai", "value2", "cn", "enable", false))).get("enable").asBoolean());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_trading_calendar_day WHERE marketItemId = ?",
                Integer.class, id));
    }

    private JsonNode group(String token, String code, Object config) throws Exception {
        return data(postJson("/param/groups", token, map("code", code, "name", "测试分类",
                "fieldConfig", config, "createdBy", 99999, "updatedBy", 99999)));
    }

    private JsonNode item(String token, long groupId, String code, Object... fields) throws Exception {
        Map<String, Object> request = map("code", code, "name", "测试明细");
        request.putAll(map(fields));
        return data(postJson("/param/groups/" + groupId + "/items", token, request));
    }

    @Test
    void crudAuditsAndFilteredPagination() throws Exception {
        String token = loginAsAdmin();
        JsonNode group = group(token, "app_page", null);
        long id = group.get("id").asLong();
        assertEquals(1L, group.get("createdBy").asLong());
        assertEquals(1L, group.get("updatedBy").asLong());
        assertEquals("admin", group.get("createdByName").asString());
        assertFalse(group.get("createdAt").isNull());
        JsonNode entry = item(token, id, "home", "value1", "/home", "extra", map("color", "blue"));
        assertEquals("blue", entry.get("extra").get("color").asString());
        assertEquals(1L, entry.get("createdBy").asLong());
        JsonNode page = data(getJson("/param/groups/" + id + "/items", token, "keyword", "home"));
        assertEquals(1, page.get("total").asInt());
        assertEquals("/home", page.get("pageData").get(0).get("value1").asString());
        JsonNode updated = data(patchJson("/param/groups/" + id + "/items/" + entry.get("id").asLong(), token,
                map("code", "home", "name", "首页", "extra", map(), "createdBy", 88, "updatedBy", 88)));
        assertEquals(1, updated.get("createdBy").asInt());
        assertEquals(1, updated.get("updatedBy").asInt());
        assertEquals("首页", updated.get("name").asString());
        assertEquals(1, data(getJson("/param/groups", token, "keyword", "app_page")).get("total").asInt());
        body(deleteJson("/param/groups/" + id, token), 400);
        data(deleteJson("/param/groups/" + id + "/items/" + entry.get("id").asLong(), token));
        data(deleteJson("/param/groups/" + id, token));
        body(getJson("/param/groups/" + id + "/items", token), 400);
    }

    @Test
    void uniqueCodesAndImmutableIdentifiers() throws Exception {
        String token = loginAsAdmin();
        long id = group(token, "unique", null).get("id").asLong();
        body(postJson("/param/groups", token, map("code", "unique", "name", "重复")), 400);
        long otherId = group(token, "UNIQUE", null).get("id").asLong();
        item(token, id, "same");
        body(postJson("/param/groups/" + id + "/items", token, map("code", "same", "name", "重复")), 400);
        item(token, otherId, "same");
        body(patchJson("/param/groups/" + id, token, map("code", "changed", "name", "修改")), 400);
        code(postJson("/param/groups", token, map("code", "bad code", "name", "非法")), 400);
    }

    @Test
    void validatesConfigTypesOptionsAndRequiredValues() throws Exception {
        String token = loginAsAdmin();
        Object config = map("fixedFields", map("value1", map("label", "地址", "type", "text", "required", true)),
                "extraFields", List.of(map("key", "mode", "label", "模式", "type", "select", "required", true,
                                "options", List.of(map("label", "页签", "value", "tab"))),
                        map("key", "count", "label", "数量", "type", "number")));
        long id = group(token, "validated", config).get("id").asLong();
        body(postJson("/param/groups/" + id + "/items", token, map("code", "a", "name", "a")), 400);
        body(postJson("/param/groups/" + id + "/items", token, map("code", "a", "name", "a",
                "value1", "/a", "extra", map("mode", "invalid"))), 400);
        body(postJson("/param/groups/" + id + "/items", token, map("code", "a", "name", "a",
                "value1", "/a", "extra", map("mode", "tab", "count", "3"))), 400);
        JsonNode saved = item(token, id, "valid", "value1", "/a", "extra", map("mode", "tab", "count", 3));
        assertEquals(3, saved.get("extra").get("count").asInt());
        body(postJson("/param/groups/" + id + "/items", token, map("code", "array", "name", "a",
                "value1", "/a", "extra", List.of(1))), 400);
    }

    @Test
    void rejectsInvalidSchemaAndIncompatibleSchemaChanges() throws Exception {
        String token = loginAsAdmin();
        body(postJson("/param/groups", token, map("code", "bad_storage_key", "name", "bad",
                "fieldConfig", map("fixedFields", map("value1", map("key", "name", "label", "别名", "type", "text"))))), 400);
        body(postJson("/param/groups", token, map("code", "bad_schema", "name", "bad",
                "fieldConfig", map("fixedFields", map("value10", map("label", "非法", "type", "text"))))), 400);
        body(postJson("/param/groups", token, map("code", "bad_defaults", "name", "bad",
                "fieldConfig", map("extraFields", List.of(map("key", "n", "label", "数量", "type", "number",
                        "defaultValue", "abc"))))), 400);
        long id = group(token, "schema_change", map("extraFields", List.of(
                map("key", "n", "label", "数量", "type", "number")))).get("id").asLong();
        item(token, id, "a", "extra", map("n", 3));
        body(patchJson("/param/groups/" + id, token, map("code", "schema_change", "name", "修改",
                "fieldConfig", map("extraFields", List.of(map("key", "n", "label", "数量", "type", "text"))))), 400);
        body(patchJson("/param/groups/" + id, token, map("code", "schema_change", "name", "修改",
                "fieldConfig", map("extraFields", List.of()))), 400);
        data(patchJson("/param/groups/" + id, token, map("code", "schema_change", "name", "修改名称",
                "fieldConfig", map("extraFields", List.of(map("key", "n", "label", "新名称", "type", "number"))))));
    }

    @Test
    void sortIsAtomicAndScopedAndLookupReturnsOnlyEnabledRows() throws Exception {
        String token = loginAsAdmin();
        long id = group(token, "sort_test", null).get("id").asLong();
        long other = group(token, "sort_other", null).get("id").asLong();
        long a = item(token, id, "a").get("id").asLong();
        long b = item(token, id, "b").get("id").asLong();
        long c = item(token, other, "c").get("id").asLong();
        body(patchJson("/param/groups/" + id + "/sort", token, map("ids", List.of(a, c))), 400);
        body(patchJson("/param/groups/" + id + "/sort", token, map("ids", List.of(a, a))), 400);
        body(patchJson("/param/groups/" + id + "/sort", token, map("ids", List.of(a))), 400);
        data(patchJson("/param/groups/" + id + "/sort", token, map("ids", List.of(b, a))));
        JsonNode ordered = data(getJson("/param/groups/" + id + "/items/order", token));
        assertEquals(b, ordered.get(0).get("id").asLong());
        assertEquals(1, ordered.get(0).get("updatedBy").asInt());
        assertEquals("b", data(getJson("/param/options/sort_test", token)).get(0).get("code").asString());
        data(patchJson("/param/groups/" + id + "/items/" + b, token,
                map("code", "b", "name", "禁用", "enable", false)));
        assertEquals(1, data(getJson("/param/options/sort_test", token)).size());
        data(postJson("/param/cache/refresh", token, map()));
        data(patchJson("/param/groups/" + id, token, map("code", "sort_test", "name", "禁用分类", "enable", false)));
        assertEquals(0, data(getJson("/param/options/sort_test", token)).size());
    }

    @Test
    void crossGroupUpdateAndReadAreRejected() throws Exception {
        String token = loginAsAdmin();
        long id = group(token, "scope_a", null).get("id").asLong();
        long other = group(token, "scope_b", null).get("id").asLong();
        long itemId = item(token, id, "a").get("id").asLong();
        body(patchJson("/param/groups/" + other + "/items/" + itemId, token, map("code", "a", "name", "a")), 400);
        body(deleteJson("/param/groups/" + other + "/items/" + itemId, token), 400);
        body(getJson("/param/groups/" + other + "/items/" + itemId, token), 400);
    }

    @Test
    void endpointsRequireLoginAndPermission() throws Exception {
        body(getJson("/param/groups", null), 401);
        String token = loginAsAdmin();
        data(postJson("/auth/current-role/switch/ROLE_QA", token, map()));
        code(getJson("/param/groups", token), 11003);
        code(postJson("/param/groups", token, map("code", "denied", "name", "禁止")), 11003);
    }

    @Test
    void menuPermissionDoesNotGrantWritePermission() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("INSERT INTO role_permissions_permission (roleId, permissionId) SELECT 2, id FROM permission WHERE code = 'ParamMgt'");
        data(postJson("/auth/current-role/switch/ROLE_QA", token, map()));
        data(getJson("/param/groups", token));
        code(postJson("/param/groups", token, map("code", "denied_write", "name", "禁止写入")), 11003);
        jdbc.update("INSERT INTO role_permissions_permission (roleId, permissionId) SELECT 2, id FROM permission WHERE code = 'ParamCreate'");
        group(token, "permitted_write", null);
        code(postJson("/param/cache/refresh", token, map()), 11003);
    }

    @Test
    void updateAndSortRecordTheActualSecondActorAndPreserveCreator() throws Exception {
        String admin = loginAsAdmin();
        long groupId = group(admin, "actor_test", null).get("id").asLong();
        long itemId = item(admin, groupId, "entry").get("id").asLong();
        var created = data(postJson("/user", admin, map("username", "param_editor")));
        long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username = ?", Long.class, "param_editor");
        data(postJson("/user/roles/add/" + userId, admin, map("roleIds", List.of(1))));
        String temporary = created.path("temporaryPassword").asString();
        String restricted = login("param_editor", temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", "ParamTest123!")));
        String editor = login("param_editor", "ParamTest123!");
        JsonNode updated = data(patchJson("/param/groups/" + groupId + "/items/" + itemId, editor,
                map("code", "entry", "name", "另一个用户编辑", "createdBy", userId, "updatedBy", 1)));
        assertEquals(1L, updated.get("createdBy").asLong());
        assertEquals(userId, updated.get("updatedBy").asLong());
        assertEquals("param_editor", updated.get("updatedByName").asString());
        data(patchJson("/param/groups/" + groupId + "/sort", editor, map("ids", List.of(itemId))));
        JsonNode group = data(getJson("/param/groups/" + groupId, editor));
        assertEquals(1L, group.get("createdBy").asLong());
        assertEquals(userId, group.get("updatedBy").asLong());
    }

    @Test
    void configuredDefaultsPreserveFalseZeroAndAllFixedColumns() throws Exception {
        String token = loginAsAdmin();
        Object config = map("fixedFields", map("value8", map("label", "默认数量", "type", "number", "defaultValue", 0)),
                "extraFields", List.of(map("key", "enabled", "label", "开关", "type", "boolean", "required", true, "defaultValue", false)));
        long id = group(token, "defaults", config).get("id").asLong();
        JsonNode row = item(token, id, "default", "value5", "五", "value6", "六", "value7", "七", "value9", "九");
        assertEquals("0", row.get("value8").asString());
        assertFalse(row.get("extra").get("enabled").asBoolean());
        assertEquals("五", row.get("value5").asString());
        assertEquals("六", row.get("value6").asString());
        assertEquals("七", row.get("value7").asString());
        assertEquals("九", row.get("value9").asString());
    }
}
