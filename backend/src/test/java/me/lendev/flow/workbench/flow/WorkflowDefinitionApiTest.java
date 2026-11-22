package me.lendev.flow.workbench.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

class WorkflowDefinitionApiTest extends ApiTestBase {

    private static final String DEFINITIONS = "/workflow/definitions";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void storesNotificationScenesByRevisionAndDistinguishesMissingFieldsFromExplicitNull() throws Exception {
        String token = loginAsAdmin();
        String sceneCode = "workflow.test.approval";
        jdbc.update("""
                INSERT INTO sys_notification_scene
                    (scene_code, name_cn, name_en, scene_type, enabled, in_app_title_template, updated_at)
                VALUES (?, '流程进展', 'Workflow progress', 'WORKFLOW_EXTRA', TRUE, 'Workflow progress', CURRENT_TIMESTAMP)
                """, sceneCode);
        var node = map("key", "review", "order", 1, "title", "Review", "mode", "ALL", "roleCode", "ROLE_QA",
                "onApprovedSceneCode", sceneCode);
        var request = map("code", "WORKFLOW_NOTICE_VERSION", "name", "Review", "fields", List.of(), "nodes", List.of(node));
        var created = data(postJson(DEFINITIONS, token, request));
        long id = created.path("id").asLong();
        String path = DEFINITIONS + "/" + id;
        assertEquals(sceneCode, created.path("nodes").get(0).path("onApprovedSceneCode").asString());
        var choices = data(getJson(DEFINITIONS + "/notification-scenes", token));
        assertTrue(choices.valueStream().anyMatch(scene -> sceneCode.equals(scene.path("sceneCode").asString())));
        assertFalse(choices.valueStream().anyMatch(scene ->
                "workflow.task.assigned".equals(scene.path("sceneCode").asString())));
        assertEquals("NO_GROUPS", data(getJson(path, token)).path("notificationWarnings").get(0).path("reason").asString());
        assertFalse(jdbc.queryForObject("SELECT stepsJson FROM wf_definition WHERE id = ?", String.class, id)
                .contains("onApprovedSceneCode"));

        node.remove("onApprovedSceneCode");
        request.put("version", created.path("version").asLong());
        var preserved = data(patchJson(path, token, request));
        assertEquals(sceneCode, preserved.path("nodes").get(0).path("onApprovedSceneCode").asString());
        var published = data(postJson(path + "/publish", token, map("version", preserved.path("version").asLong())));
        assertEquals("NO_GROUPS", published.path("notificationWarnings").get(0).path("reason").asString());

        request.put("sourceDefinitionId", id);
        request.remove("version");
        var copied = data(postJson(DEFINITIONS, token, request));
        long copyId = copied.path("id").asLong();
        assertEquals(sceneCode, copied.path("nodes").get(0).path("onApprovedSceneCode").asString());
        node.put("onApprovedSceneCode", null);
        request.put("version", copied.path("version").asLong());
        var cleared = data(patchJson(DEFINITIONS + "/" + copyId, token, request));
        assertTrue(cleared.path("nodes").get(0).path("onApprovedSceneCode").isNull());
        assertEquals(List.of(id), jdbc.queryForList(
                "SELECT definition_id FROM wf_definition_notification_ref WHERE scene_code = ?", Long.class, sceneCode));
        assertEquals(sceneCode, data(getJson(path, token)).path("nodes").get(0).path("onApprovedSceneCode").asString());
    }

    @Test
    void refusesInvalidSceneKindsAndRechecksARevisedSceneOnPublication() throws Exception {
        String token = loginAsAdmin();
        String scene = "workflow.test.publication";
        jdbc.update("""
                INSERT INTO sys_notification_scene (scene_code,name_cn,scene_type,in_app_title_template,updated_at)
                VALUES (?,'复核通知','WORKFLOW_EXTRA','流程复核',CURRENT_TIMESTAMP)
                """, scene);
        var leaf = map("key", "review", "order", 1, "title", "Review", "mode", "ALL", "roleCode", "ROLE_QA",
                "onApprovedSceneCode", 17);
        var request = map("code", "WORKFLOW_NOTICE_VALIDATION", "name", "Review", "fields", List.of(), "nodes", List.of(leaf));
        body(postJson(DEFINITIONS, token, request), 400);
        leaf.put("onApprovedSceneCode", "workflow.task.assigned");
        body(postJson(DEFINITIONS, token, request), 400);
        var summary = map("key", "parent", "order", 1, "title", "Summary", "onRejectedSceneCode", scene);
        leaf.put("parentKey", "parent");
        leaf.remove("onApprovedSceneCode");
        request.put("nodes", List.of(summary, leaf));
        body(postJson(DEFINITIONS, token, request), 400);
        leaf.remove("parentKey");
        leaf.put("onApprovedSceneCode", scene);
        request.put("nodes", List.of(leaf));
        var draft = data(postJson(DEFINITIONS, token, request));
        jdbc.update("UPDATE sys_notification_scene SET enabled=FALSE, version=1 WHERE scene_code=?", scene);

        var choices = data(getJson(DEFINITIONS + "/notification-scenes", token));
        assertFalse(choices.valueStream().anyMatch(item -> scene.equals(item.path("sceneCode").asString())));
        String path = DEFINITIONS + "/" + draft.path("id").asLong();
        body(postJson(path + "/publish", token, map("version", draft.path("version").asLong())), 400);
        assertEquals(scene, data(getJson(path, token)).path("nodes").get(0).path("onApprovedSceneCode").asString());
    }

    @Test
    @Sql("/db/seed/workflow-examples.sql")
    void createsLeaveDraftWithInferredAndExplicitlyEmptyEditableFields() throws Exception {
        String token = loginAsAdmin();
        addDemoMembers();
        assignDemoPrimaries();
        var request = objectMapper.readTree("""
                {"code":"WF_DEMO_QJ","name":"请假流程","fields":["qj_days","qj"],"nodes":[
                  {"key":"node_1","parentKey":null,"order":1,"title":"申请","roleCode":"WF_DEMO_REQUESTER",
                   "mode":"PRIMARY","editableFields":null,"requiredFields":[],"dueOffsetDays":null,"roleCodes":null},
                  {"key":"node_2","parentKey":null,"order":2,"title":"审核","roleCode":"WF_DEMO_SECURITY",
                   "mode":"PRIMARY","editableFields":[],"requiredFields":[],"dueOffsetDays":null,"roleCodes":null}
                ]}
                """);
        var saved = data(postJson(DEFINITIONS, token, request));
        assertEquals("DRAFT", saved.path("status").asString());
        var persisted = data(getJson(DEFINITIONS + "/" + saved.path("id").asLong(), token));
        assertEquals(saved, persisted);
        assertTrue(persisted.path("nodes").get(0).path("editableFields").isNull());
        assertEquals(objectMapper.createArrayNode(), persisted.path("nodes").get(1).path("editableFields"));
    }

    @Test
    @Sql("/db/seed/workflow-examples.sql")
    void checksAllPrimaryNodesInPreflightAndRejectsInvalidDraftWritesAndPublication() throws Exception {
        String token = loginAsAdmin();
        addDemoMembers();
        var nodes = List.of(
                map("key", "apply", "title", "申请", "order", 1, "mode", "PRIMARY", "roleCode", "WF_DEMO_REQUESTER"),
                map("key", "review", "title", "部门会审", "order", 2),
                map("key", "security", "title", "审核", "order", 1, "parentKey", "review",
                        "mode", "PRIMARY", "roleCode", "WF_DEMO_SECURITY"),
                map("key", "budget", "title", "预算", "order", 2, "parentKey", "review",
                        "mode", "BROADCAST", "roleCode", "WF_DEMO_BUDGET"));
        String checkPath = DEFINITIONS + "/validate-primary-handlers";
        var draft = map("code", "PRIMARY_CHECK", "name", "Primary check", "fields", List.of(), "nodes", nodes);
        var invalid = data(postJson(checkPath, token, map("nodes", nodes)));
        assertFalse(invalid.path("valid").asBoolean());
        var missing = invalid.path("missingPrimaryHandlers");
        assertEquals(List.of("apply", "security"), missing.valueStream().map(issue -> issue.path("nodeKey").asString()).toList());
        assertEquals("WF_DEMO_SECURITY", missing.get(1).path("roleCode").asString());
        assertEquals("流程示例 · 安全", missing.get(1).path("roleName").asString());
        assertEquals("PRIMARY_UNAVAILABLE", missing.get(1).path("reason").asString());
        var rejected = body(postJson(DEFINITIONS, token, draft), 400);
        assertEquals(invalid, rejected.path("data"));
        assertTrue(rejected.path("message").asString().contains("流程主备"));
        assertTrue(rejected.path("message").asString().contains("审核"));

        assignDemoPrimaries();
        var valid = data(postJson(checkPath, token, map("nodes", nodes)));
        assertTrue(valid.path("valid").asBoolean());
        assertTrue(valid.path("missingPrimaryHandlers").isEmpty());
        var saved = data(postJson(DEFINITIONS, token, draft));
        String path = DEFINITIONS + "/" + saved.path("id").asLong();
        var version = saved.path("version").asLong();
        draft.put("version", version);
        jdbc.update("UPDATE role SET enable = 0 WHERE code = 'WF_DEMO_SECURITY'");
        var editError = body(patchJson(path, token, draft), 400).path("data");
        var publishError = body(postJson(path + "/publish", token, map("version", version)), 400).path("data");
        assertEquals(editError, publishError);
        JsonNode issue = publishError.path("missingPrimaryHandlers").get(0);
        assertEquals("security", issue.path("nodeKey").asString());
        assertEquals("ROLE_DISABLED", issue.path("reason").asString());
        assertEquals(saved, data(getJson(path, token)));

        jdbc.update("UPDATE role SET enable = 1 WHERE code = 'WF_DEMO_SECURITY'");
        assertEquals("PUBLISHED", data(postJson(path + "/publish", token, map("version", version))).path("status").asString());
    }

    @Test
    void identifiesTheNodeAndFieldWhenARequiredFieldIsReadOnly() throws Exception {
        String token = loginAsAdmin();
        var request = map("code", "V2_INVALID_FIELDS", "name", "Review", "fields", List.of("qj_days"),
                "nodes", List.of(map("key", "review", "order", 1, "title", "审核", "mode", "PRIMARY",
                        "editableFields", List.of(), "requiredFields", List.of("qj_days"))));
        var error = body(postJson(DEFINITIONS, token, request), 400);
        assertEquals("节点「审核」的必填字段「qj_days」不可编辑。请将它设为可编辑，或取消必填。", error.path("message").asString());
    }

    @Test
    @Sql(scripts = {"/db/seed/workflow-examples.sql", "/db/seed/workflow-examples.sql"})
    void exampleDraftsCanBePublishedAndStayOutsideLegacyApi() throws Exception {
        String token = loginAsAdmin();
        addDemoMembers();
        assignDemoPrimaries();
        var drafts = data(getJson(DEFINITIONS, token)).path("pageData");
        assertEquals(5, drafts.size());
        assertTrue(data(getJson("/workflow/catalog", token)).isEmpty());

        for (var draft : drafts) {
            long id = draft.path("id").asLong();
            long version = draft.path("version").asLong();
            var publishRequest = map("version", version);
            var published = data(postJson(DEFINITIONS + "/" + id + "/publish", token, publishRequest));
            assertEquals("PUBLISHED", published.path("status").asString());
            assertTrue(published.path("fields").valueStream().allMatch(field -> field.path("config").isObject()));
            body(postJson(DEFINITIONS + "/" + id + "/publish", token, publishRequest), 409);
        }
        assertEquals(5, data(getJson("/workflow/catalog", token)).size());
        var firstPage = data(getJson("/workflow/catalog/page", token, "pageSize", 2));
        var lastPage = data(getJson("/workflow/catalog/page", token, "pageNo", 3, "pageSize", 2));
        assertEquals(5, firstPage.path("total").asInt());
        assertEquals(5, lastPage.path("total").asInt());
        assertEquals(List.of("WF_DEMO_ACCESS", "WF_DEMO_CONTRACT"),
                firstPage.path("pageData").valueStream().map(row -> row.path("code").asString()).toList());
        assertEquals("WF_DEMO_PURCHASE", lastPage.path("pageData").get(0).path("code").asString());
        assertEquals(1, lastPage.path("pageData").size());
        assertTrue(data(getJson("/workbench/flow-definitions", token, "published", true)).valueStream()
                .noneMatch(item -> item.path("code").asString().startsWith("WF_DEMO_")));
    }

    private void addDemoMembers() {
        jdbc.update("INSERT INTO user_roles_role (userId, roleId) SELECT 1,id FROM role WHERE code LIKE 'WF_DEMO_%'");
    }

    private void assignDemoPrimaries() {
        jdbc.update("INSERT INTO wf_role_handler (userId, roleId, kind, primaryRoleId) SELECT 1,id,'PRIMARY',id FROM role WHERE code LIKE 'WF_DEMO_%'");
    }

    @Test
    void validatesParallelFieldOwnershipAndKeepsOldRevisionAvailable() throws Exception {
        String token = loginAsAdmin();
        var draft = map("code", "V2_REVIEW", "name", "Review", "fields", List.of("shared"),
                "nodes", List.of(
                        map("key", "review", "order", 1, "title", "Review"),
                        map("key", "finance", "parentKey", "review", "order", 1, "title", "Finance",
                                "roleCode", "ROLE_QA", "mode", "PRIMARY", "editableFields", List.of("shared")),
                        map("key", "budget", "parentKey", "review", "order", 2, "title", "Budget",
                                "roleCode", "ROLE_QA", "mode", "BROADCAST", "editableFields", List.of("shared"))));
        body(postJson(DEFINITIONS, token, draft), 400);

        draft.put("fields", List.of());
        draft.put("nodes", List.of(map("key", "review", "order", 1, "title", "Review",
                "roleCode", "ROLE_QA", "mode", "ALL")));
        var saved = data(postJson(DEFINITIONS, token, draft));
        long id = saved.path("id").asLong();
        String path = DEFINITIONS + "/" + id;
        draft.put("version", saved.path("version").asLong());
        draft.put("name", "Review updated");
        var updated = data(patchJson(path, token, draft));
        assertEquals("Review updated", updated.path("name").asString());
        body(patchJson(path, token, draft), 409);

        var published = data(postJson(path + "/publish", token, map("version", updated.path("version").asLong())));
        var revision = data(postJson(DEFINITIONS, token, draft));
        assertEquals(2, revision.path("revision").asInt());
        var available = data(getJson("/workflow/catalog/page", token)).path("pageData").valueStream()
                .filter(row -> "V2_REVIEW".equals(row.path("code").asString())).findFirst().orElseThrow();
        assertEquals(id, available.path("id").asLong());
        assertTrue(saved.path("publishedAt").isNull());
        String publishedAt = published.path("publishedAt").asString();
        assertTrue(!publishedAt.isBlank());
        publishedAt = data(getJson(path, token)).path("publishedAt").asString();
        var current = data(getJson(DEFINITIONS, token, "latestOnly", true)).path("pageData").valueStream()
                .filter(item -> "V2_REVIEW".equals(item.path("code").asString())).toList();
        assertEquals(1, current.size());
        assertEquals(revision.path("id").asLong(), current.get(0).path("id").asLong());
        var filtered = data(getJson(DEFINITIONS, token, "latestOnly", true, "keyword", "v2_review", "status", "DRAFT"));
        assertEquals(1, filtered.path("total").asInt());
        assertEquals(revision.path("id").asLong(), filtered.path("pageData").get(0).path("id").asLong());
        assertEquals(0, data(getJson(DEFINITIONS, token, "latestOnly", true,
                "keyword", "Review updated", "status", "PUBLISHED")).path("total").asInt());
        assertEquals(0, data(getJson(DEFINITIONS, token, "keyword", "V2%REVIEW")).path("total").asInt());
        String revisionPath = DEFINITIONS + "/" + revision.path("id").asLong();
        var history = data(getJson(revisionPath + "/history", token)).path("pageData");
        assertEquals(1, history.size());
        assertEquals(id, history.get(0).path("id").asLong());
        var started = data(postJson("/workflow/instances", token,
                map("definitionId", id, "requestKey", "version-one", "title", "Version one instance")));
        assertEquals(0, data(getJson(revisionPath + "/instances", token)).path("total").asInt());
        assertEquals(started.path("instance").path("id").asLong(),
                data(getJson(path + "/instances", token)).path("pageData").get(0).path("id").asLong());
        var retired = data(postJson(path + "/retire", token, map("version", published.path("version").asLong())));
        assertEquals("RETIRED", retired.path("status").asString());
        assertEquals(publishedAt, retired.path("publishedAt").asString());

        var member = data(postJson("/user", token, map("username", "template_member", "roleIds", List.of(2))));
        String firstLogin = login("template_member", member.path("temporaryPassword").asString());
        String password = "Template-Review25!";
        data(postJson("/auth/password", firstLogin, map("oldPassword", member.path("temporaryPassword").asString(),
                "newPassword", password)));
        String memberToken = login("template_member", password);
        code(getJson(path + "/instances", memberToken), 11003);
        code(getJson(revisionPath + "/history", memberToken), 11003);
        assertTrue(data(getJson("/workflow/catalog", token)).valueStream()
                .noneMatch(item -> "V2_REVIEW".equals(item.path("code").asString())));
    }
}
