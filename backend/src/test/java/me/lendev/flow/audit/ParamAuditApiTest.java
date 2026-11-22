package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

class ParamAuditApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private long group(String token, String code, Object config) throws Exception {
        return data(postJson("/param/groups", token, map("code", code, "name", "分类", "fieldConfig", config))).path("id").asLong();
    }

    private long item(String token, long groupId, String code, Object... fields) throws Exception {
        Map<String, Object> body = map("code", code, "name", "明细");
        body.putAll(map(fields));
        return data(postJson("/param/groups/" + groupId + "/items", token, body)).path("id").asLong();
    }

    private JsonNode latest(String operation) {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1", String.class, operation));
    }

    private List<String> paths(JsonNode changes) {
        List<String> paths = new ArrayList<>();
        for (JsonNode change : changes) {
            paths.add(change.path("path").asString());
        }
        return paths;
    }

    @Test
    void crudHasCompleteSnapshotsActorAndParentHistoryWithoutTechnicalNoise() throws Exception {
        String token = loginAsAdmin();
        int groupUpdates = jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE operationCode='system.param.group.update'", Integer.class);
        long groupId = group(token, "audit_crud", null);
        long id = item(token, groupId, "home", "value1", "secret-before", "extra", map("opaque", "private-before"));
        var created = latest("system.param.item.create");
        assertEquals("1", created.path("actor").path("id").asString());
        assertEquals("admin", created.path("actor").path("account").asString());
        assertEquals("Admin", created.path("actor").path("name").asString());
        assertEquals(String.valueOf(groupId), created.path("objects").get(0).path("object").path("parentId").asString());
        assertTrue(created.path("objects").get(0).path("beforeData").isNull());
        assertEquals("secret-before", created.path("objects").get(0).path("afterData").path("value1").path("value").asString());
        assertEquals("private-before", created.path("objects").get(0).path("afterData").path("extra").path("value").path("opaque").asString());
        data(patchJson("/param/groups/" + groupId + "/items/" + id, token,
                map("code", "home", "name", "首页", "value1", "secret-after", "extra", map("opaque", "private-after"))));
        var updated = latest("system.param.item.update");
        assertTrue(updated.path("summary").asString().contains("首页"));
        var object = updated.path("objects").get(0);
        assertEquals("明细", object.path("beforeData").path("name").path("value").asString());
        assertEquals("首页", object.path("afterData").path("name").path("value").asString());
        assertTrue(paths(object.path("changes")).contains("value1"));
        assertEquals("secret-before", object.path("beforeData").path("value1").path("value").asString());
        assertEquals("secret-after", object.path("afterData").path("value1").path("value").asString());
        assertEquals("private-after", object.path("afterData").path("extra").path("value").path("opaque").asString());
        assertFalse(object.path("afterData").path("value1").path("redacted").asBoolean());
        assertFalse(updated.toString().contains("updatedAt"));
        assertFalse(updated.toString().contains("createdBy"));
        assertEquals(String.valueOf(groupId), latest("system.param.group.create").path("objects").get(0).path("object").path("objectId").asString());
        assertEquals(groupUpdates, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE operationCode='system.param.group.update'", Integer.class));
        data(deleteJson("/param/groups/" + groupId + "/items/" + id, token));
        var deleted = latest("system.param.item.delete").path("objects").get(0);
        assertTrue(deleted.path("afterData").isNull());
        assertEquals("首页", deleted.path("object").path("objectName").asString());
    }

    @Test
    void fixedAndConfiguredExtraValuesRetainDisplayLabelsWithoutAnAllowlist() throws Exception {
        String token = loginAsAdmin();
        var config = map("fixedFields", map("value1", map("label", "开关", "type", "select", "options",
                        List.of(map("value", "OPEN", "label", "开启"), map("value", "CLOSED", "label", "关闭")))),
                "extraFields", List.of(map("key", "flag", "label", "需要登录", "type", "boolean")));
        long groupId = group(token, "audit_visible", config);
        long id = item(token, groupId, "home", "value1", "OPEN", "extra", map("flag", false, "unlisted", "never-save-extra"));
        data(patchJson("/param/groups/" + groupId + "/items/" + id, token,
                map("code", "home", "name", "明细", "value1", "CLOSED", "extra", map("flag", true, "unlisted", "never-save-new-extra"))));
        JsonNode changes = latest("system.param.item.update").path("objects").get(0).path("changes");
        boolean fixed = false, flag = false;
        for (JsonNode change : changes) {
            if ("value1".equals(change.path("path").asString())) {
                fixed = true;
                assertEquals("OPEN", change.path("before").path("value").asString());
                assertEquals("开启", change.path("before").path("display").asString());
                assertEquals("关闭", change.path("after").path("display").asString());
            }
            if ("extra.flag".equals(change.path("path").asString())) {
                flag = true;
                assertFalse(change.path("before").path("value").asBoolean());
                assertTrue(change.path("after").path("value").asBoolean());
            }
        }
        assertTrue(fixed);
        assertTrue(flag);
        assertTrue(changes.toString().contains("never-save-extra"));
        assertTrue(changes.toString().contains("never-save-new-extra"));
    }

    @Test
    void sortingIsOneEventWithOnlySortChangesAndEmptySortIsNoChange() throws Exception {
        String token = loginAsAdmin();
        long groupId = group(token, "audit_sort", null);
        long a = item(token, groupId, "a", "value1", "never-copy-full-value");
        long b = item(token, groupId, "b");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox", Long.class);
        data(patchJson("/param/groups/" + groupId + "/sort", token, map("ids", List.of(b, a))));
        assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox", Long.class));
        var event = latest("system.param.item.sort");
        assertEquals(2, event.path("objects").size());
        for (JsonNode object : event.path("objects")) {
            assertEquals(List.of("sort"), paths(object.path("changes")));
        }
        assertFalse(event.toString().contains("never-copy-full-value"));
        long empty = group(token, "audit_empty", null);
        data(patchJson("/param/groups/" + empty + "/sort", token, map("ids", List.of())));
        assertEquals("NO_CHANGE", latest("system.param.item.sort").path("result").asString());
        assertEquals("REFERENCE", latest("system.param.item.sort").path("objects").get(0).path("entryType").asString());
    }

    @Test
    void classificationSortingAndNoOpDoNotReportTechnicalChanges() throws Exception {
        String token = loginAsAdmin();
        var ids = new java.util.ArrayList<Long>();
        for (var group : data(getJson("/param/groups/order", token))) {
            long id = group.path("id").asLong();
            ids.add(id);
            jdbc.update("UPDATE sys_param_group SET sort = ? WHERE id = ?", ids.size(), id);
        }
        long a = group(token, "audit_group_a", null);
        long b = group(token, "audit_group_b", null);
        ids.add(b);
        ids.add(a);
        data(patchJson("/param/groups/sort", token, map("ids", ids)));
        var event = latest("system.param.group.sort");
        assertEquals(ids.size(), event.path("objects").size());
        for (var entry : event.path("objects")) {
            long id = Long.parseLong(entry.path("object").path("objectId").asString());
            assertEquals(id == a || id == b ? List.of("sort") : List.of(), paths(entry.path("changes")));
        }
        assertFalse(event.toString().contains("version"));
        data(patchJson("/param/groups/" + a, token, map("code", "audit_group_a", "name", "分类", "sort", ids.size())));
        assertEquals("NO_CHANGE", latest("system.param.group.update").path("result").asString());
    }

    @Test
    void invalidIdentifiersKeepExisting400ResponseAndConfigDefaultsAreRecordedInFull() throws Exception {
        String token = loginAsAdmin();
        body(patchJson("/param/groups/0/items/1", token, map("code", "home", "name", "首页")), 400);
        body(deleteJson("/param/groups/1/items/0", token), 400);
        long groupId = group(token, "audit_defaults", map("fixedFields", map("value1",
                map("label", "私密配置", "defaultValue", "never-save-default"))));
        var field = latest("system.param.group.create").path("objects").get(0).path("afterData").path("fieldConfig");
        assertEquals("never-save-default", field.path("value").path("fixedFields").path("value1").path("defaultValue").asString());
        assertFalse(field.path("redacted").asBoolean());
        data(patchJson("/param/groups/" + groupId, token, map("code", "audit_defaults", "name", "分类",
                "remark", "分类备注", "fieldConfig", map("fixedFields", map("value1",
                        map("label", "私密配置", "defaultValue", "updated-default"))))));
        var updated = latest("system.param.group.update").path("objects").get(0);
        assertEquals("never-save-default", updated.path("beforeData").path("fieldConfig").path("value")
                .path("fixedFields").path("value1").path("defaultValue").asString());
        assertEquals("updated-default", updated.path("afterData").path("fieldConfig").path("value")
                .path("fixedFields").path("value1").path("defaultValue").asString());
        assertEquals("分类备注", updated.path("afterData").path("remark").path("value").asString());
    }

    @Test
    void allNineValuesRemarksAndNestedUnregisteredExtraAreRecordedWithoutMasking() throws Exception {
        String token = loginAsAdmin();
        long groupId = group(token, "audit_full", null);
        Map<String, Object> values = map("code", "full", "name", "完整数据", "remark", "原始备注",
                "extra", map("unknown", map("nested", List.of(false, 0, "原始扩展值")), "empty", "", "unset", null));
        for (int i = 1; i <= 9; i++) {
            values.put("value" + i, "原始值" + i);
        }
        data(postJson("/param/groups/" + groupId + "/items", token, values));
        var snapshot = latest("system.param.item.create").path("objects").get(0).path("afterData");
        for (int i = 1; i <= 9; i++) {
            assertEquals("原始值" + i, snapshot.path("value" + i).path("value").asString());
            assertFalse(snapshot.path("value" + i).path("redacted").asBoolean());
        }
        assertEquals("原始备注", snapshot.path("remark").path("value").asString());
        assertEquals(objectMapper.valueToTree(values.get("extra")), snapshot.path("extra").path("value"));
        assertFalse(snapshot.path("extra").path("redacted").asBoolean());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initialAuditInsertFailureRollsBackParameterAndCacheVersion() throws Exception {
        String token = loginAsAdmin();
        long groupId = group(token, "audit_rollback", null);
        long id = item(token, groupId, "home", "value1", "original");
        long version = jdbc.queryForObject("SELECT version FROM sys_param_group WHERE id=?", Long.class, groupId);
        long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM sys_audit_outbox", Long.class);
        boolean constraint = false;
        try {
            jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_param_audit CHECK (id <= " + existing
                    + " OR operationCode <> 'system.param.item.update')");
            constraint = true;
            body(patchJson("/param/groups/" + groupId + "/items/" + id, token,
                    map("code", "home", "name", "明细", "value1", "overwritten")), 503);
            assertEquals("original", jdbc.queryForObject("SELECT value1 FROM sys_param_item WHERE id=?", String.class, id));
            assertEquals(version, jdbc.queryForObject("SELECT version FROM sys_param_group WHERE id=?", Long.class, groupId));
        } finally {
            if (constraint) {
                jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_param_audit");
            }
            jdbc.update("DELETE FROM sys_param_item WHERE groupId=?", groupId);
            jdbc.update("DELETE FROM sys_param_group WHERE id=?", groupId);
        }
    }
}
