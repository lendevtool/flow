package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.*;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditQueryApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditWorker worker;
    private final List<Long> groups = new ArrayList<>();

    @BeforeEach
    void cleanAudit() {
        jdbc.update("DELETE FROM sys_operation_change");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM sys_audit_outbox");
    }

    @AfterEach
    void cleanup() {
        for (Long id : groups) {
            jdbc.update("DELETE FROM sys_param_item WHERE groupId=?", id);
            jdbc.update("DELETE FROM sys_param_group WHERE id=?", id);
        }
        jdbc.update("UPDATE profile SET nickName='Admin' WHERE userId=1");
        jdbc.update("DELETE FROM role_permissions_permission WHERE roleId IN (SELECT id FROM role WHERE code='AUDIT_TEST_VIEWER')");
        jdbc.update("DELETE FROM user_roles_role WHERE roleId IN (SELECT id FROM role WHERE code='AUDIT_TEST_VIEWER')");
        jdbc.update("DELETE FROM profile WHERE userId IN (SELECT id FROM `user` WHERE username='audit_test_viewer')");
        jdbc.update("DELETE FROM `user` WHERE username='audit_test_viewer'");
        jdbc.update("DELETE FROM role WHERE code='AUDIT_TEST_VIEWER'");
    }

    private long group(String token) throws Exception {
        long id = data(postJson("/param/groups", token, map("code", "aq_" + UUID.randomUUID().toString().substring(0, 8),
                "name", "查询分类"))).path("id").asLong();
        groups.add(id);
        return id;
    }

    @Test
    void searchesFixedColumnsAndObjectHistoryThenPaginatesCompleteDetails() throws Exception {
        String token = loginAsAdmin();
        long group = group(token);
        long item = data(postJson("/param/groups/" + group + "/items", token,
                map("code", "home", "name", "首页", "value1", "never-save-query-secret"))).path("id").asLong();
        worker.runOnce();
        jdbc.update("UPDATE profile SET nickName='Renamed' WHERE userId=1");
        var page = data(getJson("/audit/logs", token, "moduleCode", "PARAM", "actorId", "1", "objectType", "PARAM_ITEM",
                "objectId", item, "parentType", "PARAM_GROUP", "parentId", group));
        assertEquals(1, page.path("total").asInt());
        var row = page.path("pageData").get(0);
        assertEquals("Admin", row.path("actorName").asString());
        assertFalse(row.has("payload"));
        assertFalse(row.has("beforeData"));
        assertFalse(row.has("changes"));
        assertEquals(1, data(getJson("/audit/logs", token, "objectType", "PARAM_ITEM", "objectId", item,
                "pageNo", Integer.MIN_VALUE)).path("pageData").size());
        assertTrue(row.path("id").isString());
        String id = row.path("id").asString();
        var detail = data(getJson("/audit/logs/" + id, token, "pageSize", 1));
        assertEquals(1, detail.path("objects").path("total").asInt());
        assertTrue(detail.toString().contains("never-save-query-secret"));
        var change = detail.path("objects").path("pageData").get(0);
        assertTrue(change.path("logId").isString());
        assertEquals(id, change.path("logId").asString());
        var recordedValue = change.path("afterData").path("value1");
        assertEquals("never-save-query-secret", recordedValue.path("value").asString());
        assertFalse(recordedValue.path("redacted").asBoolean());
        assertTrue(change.path("beforeData").isNull());
        assertEquals(0, data(getJson("/audit/logs", token, "actorKeyword", "' OR 1=1 --")).path("total").asInt());
        body(getJson("/audit/logs", token, "startTime", "2026-11-07T00:00:00Z", "endTime", "2026-11-06T00:00:00Z"), 400);
        worker.runOnce();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operationCode='system.audit.detail.view'", Integer.class));
    }

    @Test
    void viewingPermissionsDoNotGrantDetailsExportsOrRetries() throws Exception {
        jdbc.update("INSERT INTO role(code,name,enable) VALUES ('AUDIT_TEST_VIEWER','日志查看者',1)");
        long role = jdbc.queryForObject("SELECT id FROM role WHERE code='AUDIT_TEST_VIEWER'", Long.class);
        jdbc.update("INSERT INTO `user`(username,password,identityKey,enable,createTime,updateTime)"
                + " SELECT 'audit_test_viewer',password,RANDOM_UUID(),1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM `user` WHERE id=1");
        long user = jdbc.queryForObject("SELECT id FROM `user` WHERE username='audit_test_viewer'", Long.class);
        jdbc.update("INSERT INTO user_roles_role(userId,roleId) VALUES (?,?)", user, role);
        jdbc.update("INSERT INTO role_permissions_permission(roleId,permissionId) SELECT ?,id FROM permission WHERE code='AuditView'", role);
        String token = login("audit_test_viewer", ADMIN_PASSWORD);
        worker.runOnce();
        var page = data(getJson("/audit/logs", token));
        assertTrue(page.path("total").asInt() > 0);
        String id = page.path("pageData").get(0).path("id").asString();
        code(getJson("/audit/logs/" + id, token), 11003);
        code(getJson("/audit/logs/export", token), 11003);
        code(getJson("/audit/outbox", token), 11003);
        code(postJson("/audit/outbox/1/retry", token, map("rowVersion", 0)), 11003);
        assertNotNull(data(getJson("/audit/options", token)).path("operations"));
    }

    @Test
    void retryUsesVersionAndOnlyRedeliversAuditWithoutRepeatingParameterMutation() throws Exception {
        String token = loginAsAdmin();
        long group = group(token);
        long id = jdbc.queryForObject("SELECT id FROM sys_audit_outbox WHERE operationCode='system.param.group.create' ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("UPDATE sys_audit_outbox SET status='DEAD',failureCount=20,nextRetryAt=NULL WHERE id=?", id);
        var page = data(getJson("/audit/outbox", token, "status", "DEAD"));
        var queued = page.path("pageData").get(0);
        assertFalse(queued.has("payload"));
        long version = queued.path("rowVersion").asLong();
        data(postJson("/audit/outbox/" + id + "/retry", token, map("rowVersion", version)));
        body(postJson("/audit/outbox/" + id + "/retry", token, map("rowVersion", version)), 400);
        worker.runOnce();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_param_group WHERE id=?", Integer.class, group));
        assertEquals(1, jdbc.queryForObject("SELECT manualRetryCount FROM sys_audit_outbox WHERE id=?", Integer.class, id));
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM sys_audit_outbox WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operationCode='system.audit.retry' AND result='SUCCESS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operationCode='system.audit.retry' AND result='FAILURE'", Integer.class));
        assertNotNull(data(getJson("/audit/status", token)).path("counts"));
    }

    @Test
    void exportsSafeCsvAndAuditsActualCriteriaAndRowCount() throws Exception {
        jdbc.update("UPDATE profile SET nickName='=2+2' WHERE userId=1");
        String token = loginAsAdmin();
        worker.runOnce();
        var response = mockMvc.perform(get("/audit/logs/export").param("moduleCode", "AUTH")
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(response.getContentType().startsWith("text/csv"));
        String csv = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=2+2"));
        assertFalse(csv.contains(token));
        worker.runOnce();
        long exported = jdbc.queryForObject("SELECT id FROM sys_operation_log WHERE operationCode='system.audit.export'", Long.class);
        var detail = data(getJson("/audit/logs/" + exported, token));
        var fact = detail.path("objects").path("pageData").get(0).path("afterData");
        assertEquals(1, fact.path("rowCount").path("value").asInt());
        assertEquals("AUTH", fact.path("moduleCode").path("value").asString());
    }
}
