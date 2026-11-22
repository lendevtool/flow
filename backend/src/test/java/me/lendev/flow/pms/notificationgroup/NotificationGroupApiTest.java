package me.lendev.flow.pms.notificationgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import me.lendev.flow.audit.AuditWorker;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:notification-group-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationGroupApiTest extends ApiTestBase {

    private static final String GROUPS = "/notification-groups";
    private static final String UPDATE_OPERATION = "system.notification.group.update";
    private static final String STATUS_OPERATION = "system.notification.group.status.update";
    private static final String BIND_OPERATION = "system.notification.scene.groups.replace";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditWorker worker;
    @Autowired
    NotificationRecipientResolver resolver;

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM sys_notification_scene_group");
        jdbc.update("DELETE FROM sys_notification_scene");
        jdbc.update("DELETE FROM sys_notification_group_department");
        jdbc.update("DELETE FROM sys_notification_group_user");
        jdbc.update("DELETE FROM sys_notification_group");
        jdbc.update("DELETE FROM sys_department_member WHERE department_id IN (SELECT id FROM sys_department WHERE name LIKE '通知测试%')");
        jdbc.update("DELETE FROM sys_department WHERE name = '通知测试分部'");
        jdbc.update("DELETE FROM sys_department WHERE name = '通知测试总部'");
        jdbc.update("DELETE FROM user_roles_role WHERE roleId IN (SELECT id FROM role WHERE code='NOTIFICATION_READ_TEST')");
        jdbc.update("DELETE FROM role_permissions_permission WHERE roleId IN (SELECT id FROM role WHERE code='NOTIFICATION_READ_TEST')");
        jdbc.update("DELETE FROM role WHERE code='NOTIFICATION_READ_TEST'");
        jdbc.update("DELETE FROM `user` WHERE username LIKE 'notify_fixture_%'");
        jdbc.update("DELETE FROM sys_operation_change");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM sys_audit_outbox");
    }

    @Test
    void adminCanOpenTheGroupList() throws Exception {
        String token = loginAsAdmin();

        var page = data(getJson(GROUPS, token, "keyword", "a group that does not exist"));

        assertEquals(0, page.path("total").asLong());
        assertTrue(page.path("pageData").isArray());
    }

    @Test
    void bothScenesUseCurrentDepartmentMembershipAndCanFindBindingInGroupAudit() throws Exception {
        String token = loginAsAdmin();
        long parent = department(token, null, "通知测试总部");
        long child = department(token, parent, "通知测试分部");
        long enabledMember = user("notify_fixture_member", true);
        long disabledMember = user("notify_fixture_disabled", false);
        membership(1L, parent);
        membership(1L, child);
        membership(enabledMember, child);
        membership(disabledMember, child);
        scene("notify.monthly");
        scene("notify.overdue");

        long group = createGroup(token, "财务通知", List.of(1L), List.of(map("departmentId", parent)));
        long second = createGroup(token, "额外财务通知", List.of(1L), List.of());
        bind(token, "notify.monthly", 0, List.of(group));
        bind(token, "notify.overdue", 0, List.of(second, group));
        assertEquals(List.of(1L), resolver.resolveForScene("notify.monthly").userIds());
        assertEquals(2, data(getJson(groupPath(group) + "/usages", token)).path("total").asInt());
        assertEquals("GROUP_IN_USE", body(putJson(groupPath(group) + "/status", token,
                map("enabled", false, "expectedVersion", 0)), 409).path("data").path("reason").asString());
        assertEquals("FAILURE", latest(STATUS_OPERATION).path("result").asString());

        var edited = data(putJson(groupPath(group), token, map("name", "财务通知", "expectedVersion", 0,
                "userIds", List.of(1), "departments", List.of(map("departmentId", parent, "includeDescendants", true)))));
        assertEquals(1, edited.path("version").asLong());
        assertEquals(List.of(1L, enabledMember), recipientIds(token, group));
        for (String sceneCode : List.of("notify.monthly", "notify.overdue")) {
            var resolved = resolver.resolveForScene(sceneCode);
            assertEquals("READY", resolved.status().name());
            assertEquals(List.of(1L, enabledMember), resolved.userIds());
        }
        jdbc.update("DELETE FROM sys_department_member WHERE user_id=? AND department_id=?", enabledMember, child);
        assertEquals(List.of(1L), resolver.resolveForScene("notify.monthly").userIds());
        assertEquals(List.of(1L), resolver.resolveForScene("notify.overdue").userIds());

        var changed = latest(UPDATE_OPERATION).path("objects").get(0);
        var before = changed.path("beforeData").path("departments").path("value").get(0);
        var after = changed.path("afterData").path("departments").path("value").get(0);
        assertTrue(before.path("includeDescendants").isBoolean());
        assertFalse(before.path("includeDescendants").asBoolean());
        assertTrue(after.path("includeDescendants").asBoolean());
        worker.runOnce();
        var history = data(getJson("/audit/logs", token, "moduleCode", "NOTIFICATION_GROUP",
                "objectType", "NOTIFICATION_GROUP", "objectId", group, "operationCode", BIND_OPERATION));
        assertEquals(2, history.path("total").asInt());
        bind(token, "notify.monthly", 1, List.of());
        bind(token, "notify.overdue", 1, List.of());
        assertEquals("NO_GROUPS", resolver.resolveForScene("notify.monthly").status().name());
        var disabled = data(putJson(groupPath(group) + "/status", token, map("enabled", false, "expectedVersion", 1)));
        assertFalse(disabled.path("enabled").asBoolean());
    }

    @Test
    void retainsMissingSourcesAndRecognizesUnchangedSets() throws Exception {
        String token = loginAsAdmin();
        long deleted = user("notify_fixture_deleted", true);
        long group = createGroup(token, "指定用户通知", List.of(1L, deleted), List.of());
        jdbc.update("DELETE FROM `user` WHERE id=?", deleted);

        var kept = data(putJson(groupPath(group), token, update("指定用户通知", 0, List.of(deleted, 1L))));
        assertEquals(0, kept.path("version").asLong());
        assertEquals("NO_CHANGE", latest(UPDATE_OPERATION).path("result").asString());
        assertEquals("MISSING", kept.path("users").get(1).path("status").asString());
        assertEquals(List.of(1L), recipientIds(token, group));
        body(postJson(GROUPS, token, request("无效来源", List.of(deleted), List.of())), 400);
        body(postJson(GROUPS, token, request("重复来源", List.of(1L, 1L), List.of())), 400);
        assertEquals("NAME_CONFLICT", body(postJson(GROUPS, token, request("指定用户通知", List.of(1L), List.of())), 409)
                .path("data").path("reason").asString());

        assertEquals(1, data(putJson(groupPath(group), token, update("指定用户通知", 0, List.of(1L)))).path("version").asLong());
        assertEquals("VERSION_CONFLICT", body(putJson(groupPath(group), token, update("过期名称", 0, List.of(1L))), 409)
                .path("data").path("reason").asString());
        var failure = latest(UPDATE_OPERATION);
        assertEquals("FAILURE", failure.path("result").asString());
        assertEquals(Long.toString(group), failure.path("objects").get(0).path("object").path("objectId").asString());
    }

    @Test
    void oversizedSceneReportsItsFullPreviewCountWithoutReturningAPartialSendList() throws Exception {
        String token = loginAsAdmin();
        long department = department(token, null, "通知测试总部");
        long group = createGroup(token, "批量通知", List.of(), List.of(map("departmentId", department)));
        scene("notify.bulk");
        bind(token, "notify.bulk", 0, List.of(group));
        assertEquals("NO_RECIPIENTS", resolver.resolveForScene("notify.bulk").status().name());

        jdbc.update("""
                INSERT INTO `user`(username,password,identityKey,enable)
                SELECT CONCAT('notify_fixture_bulk_', number), 'unused-fixture', RANDOM_UUID(), 1
                FROM SYSTEM_RANGE(1,5001) AS series(number)
                """);
        jdbc.update("""
                INSERT INTO sys_department_member(user_id,department_id)
                SELECT id, ? FROM `user` WHERE username LIKE 'notify_fixture_bulk_%'
                """, department);

        var preview = data(getJson(groupPath(group) + "/recipients", token, "pageSize", 1));
        assertEquals(5_001, preview.path("total").asInt());
        assertEquals(1, preview.path("pageData").size());
        var result = resolver.resolveForScene("notify.bulk");
        assertEquals("RECIPIENT_LIMIT_EXCEEDED", result.status().name());
        assertTrue(result.userIds().isEmpty());
    }

    @Test
    void acceptsSupplementaryUnicodeAtTheNameAndDescriptionLimits() throws Exception {
        String token = loginAsAdmin();
        String name = "😀".repeat(100);
        String description = "📣".repeat(500);
        var created = data(postJson(GROUPS, token,
                map("name", name, "description", description, "userIds", List.of(1L), "departments", List.of())));
        assertEquals(name, created.path("name").asString());
        assertEquals(description, created.path("description").asString());

        body(postJson(GROUPS, token, request(name + "😀", List.of(1L), List.of())), 400);
        body(postJson(GROUPS, token,
                map("name", "说明超长", "description", description + "📣", "userIds", List.of(1L), "departments", List.of())), 400);
    }

    @Test
    void rollsBackGroupAndSourcesWhenAuditCannotBeEnqueued() throws Exception {
        String token = loginAsAdmin();
        long other = user("notify_fixture_rollback", true);
        long group = createGroup(token, "修改前", List.of(1L), List.of());
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_notification_commit "
                + "CHECK (operationCode <> 'system.notification.group.update' OR phase <> 'DATABASE_COMMIT')");
        try {
            body(putJson(groupPath(group), token, update("修改后", 0, List.of(other))), 503);
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_notification_commit");
        }

        var detail = data(getJson(groupPath(group), token));
        assertEquals("修改前", detail.path("name").asString());
        assertEquals(0, detail.path("version").asLong());
        assertEquals(List.of(1L), recipientIds(token, group));
        assertEquals("FAILURE", latest(UPDATE_OPERATION).path("result").asString());
    }

    @Test
    void disablingAndBindingConcurrentlyCannotProduceADisabledReference() throws Exception {
        String token = loginAsAdmin();
        long group = createGroup(token, "并发通知", List.of(1L), List.of());
        scene("notify.race");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var binding = executor.submit(() -> {
                start.await();
                return httpStatus(putJson(GROUPS + "/scenes/notify.race/groups", token,
                        map("expectedVersion", 0, "groupIds", List.of(group))));
            });
            var disabling = executor.submit(() -> {
                start.await();
                return httpStatus(putJson(groupPath(group) + "/status", token, map("enabled", false, "expectedVersion", 0)));
            });
            start.countDown();

            var statuses = List.of(binding.get(10, TimeUnit.SECONDS), disabling.get(10, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(statuses.contains(400) || statuses.contains(409), statuses.toString());
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_notification_scene_group s
                    JOIN sys_notification_group g ON g.id=s.group_id WHERE g.enabled=FALSE
                    """, Integer.class));
        }
    }

    @Test
    void viewingGroupsDoesNotGrantWriteOrAuditDetailPermissions() throws Exception {
        String admin = loginAsAdmin();
        long group = createGroup(admin, "权限通知", List.of(1L), List.of());
        jdbc.update("INSERT INTO role(code,name,enable) VALUES ('NOTIFICATION_READ_TEST','通知查看者',1)");
        long role = jdbc.queryForObject("SELECT id FROM role WHERE code='NOTIFICATION_READ_TEST'", Long.class);
        jdbc.update("""
                INSERT INTO `user`(username,password,identityKey,enable)
                SELECT 'notify_fixture_viewer',password,RANDOM_UUID(),1 FROM `user` WHERE id=1
                """);
        jdbc.update("""
                INSERT INTO user_roles_role(userId,roleId)
                SELECT id,? FROM `user` WHERE username='notify_fixture_viewer'
                """, role);
        jdbc.update("""
                INSERT INTO role_permissions_permission(roleId,permissionId)
                SELECT ?,id FROM permission WHERE code IN ('NotificationGroupView','AuditView')
                """, role);
        String viewer = login("notify_fixture_viewer", ADMIN_PASSWORD);
        assertEquals("权限通知", data(getJson(groupPath(group), viewer)).path("name").asString());
        code(putJson(groupPath(group), viewer, update("越权修改", 0, List.of(1L))), 11003);
        code(getJson(GROUPS + "/scenes", viewer), 11003);

        worker.runOnce();
        var logs = data(getJson("/audit/logs", viewer, "moduleCode", "NOTIFICATION_GROUP",
                "objectType", "NOTIFICATION_GROUP", "objectId", group));
        assertEquals(1, logs.path("total").asInt());
        String auditId = logs.path("pageData").get(0).path("id").asString();
        code(getJson("/audit/logs/" + auditId, viewer), 11003);
        assertEquals("权限通知", data(getJson("/audit/logs/" + auditId, admin)).path("objects")
                .path("pageData").get(0).path("afterData").path("name").path("value").asString());
    }

    private long createGroup(String token, String name, List<Long> users, List<Map<String, Object>> departments) throws Exception {
        return data(postJson(GROUPS, token, request(name, users, departments))).path("id").asLong();
    }

    private Map<String, Object> request(String name, List<Long> users, List<Map<String, Object>> departments) {
        return map("name", name, "userIds", users, "departments", departments);
    }

    private Map<String, Object> update(String name, long version, List<Long> users) {
        return map("name", name, "expectedVersion", version, "userIds", users, "departments", List.of());
    }

    private String groupPath(long group) {
        return GROUPS + "/" + group;
    }

    private List<Long> recipientIds(String token, long group) throws Exception {
        var rows = data(getJson(groupPath(group) + "/recipients", token)).path("pageData");
        return StreamSupport.stream(rows.spliterator(), false).map(row -> row.path("userId").asLong()).toList();
    }

    private long department(String token, Long parent, String name) throws Exception {
        return data(postJson("/department", token, map("parentId", parent, "name", name, "sortOrder", 0, "enabled", true)))
                .asLong();
    }

    private long user(String username, boolean enabled) {
        jdbc.update("INSERT INTO `user` (username,password,identityKey,enable) VALUES (?,?,?,?)",
                username, "unused-fixture", UUID.randomUUID().toString(), enabled ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
    }

    private void membership(long user, long department) {
        jdbc.update("INSERT INTO sys_department_member (user_id,department_id) VALUES (?,?)", user, department);
    }

    private void scene(String code) {
        jdbc.update("INSERT INTO sys_notification_scene (scene_code,name_cn,updated_at) VALUES (?,?,CURRENT_TIMESTAMP)", code, code);
    }

    private void bind(String token, String scene, long version, List<Long> groups) throws Exception {
        data(putJson(GROUPS + "/scenes/" + scene + "/groups", token, map("expectedVersion", version, "groupIds", groups)));
    }

    private JsonNode latest(String operation) {
        return objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1", String.class, operation));
    }

    private ResultActions putJson(String url, String token, Object body) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(body)));
    }

    private int httpStatus(ResultActions actions) {
        return actions.andReturn().getResponse().getStatus();
    }
}
