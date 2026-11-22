package me.lendev.flow.workbench.flow;

import static me.lendev.flow.audit.AuditOperation.TODO_FLOW_DEFINE;
import static me.lendev.flow.audit.AuditOperation.TODO_FLOW_DEFINE_EDIT;
import static me.lendev.flow.audit.AuditOperation.TODO_FLOW_PUBLISH;
import static me.lendev.flow.audit.AuditOperation.TODO_FLOW_RETIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

class WorkflowDefinitionAuditApiTest extends ApiTestBase {

    private static final String DEFINITIONS = "/workflow/definitions";
    private static final String OBJECT_TYPE = "WORKFLOW_DEFINITION";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private AuditStore store;

    @Test
    void recordsPersistedTemplateChangesAndFrozenPublishingRules() throws Exception {
        String token = loginAsAdmin();
        long dictionary = data(getJson("/field-definition/dictionaries", token, "keyword", "FIELD_TEXT_FORMAT"))
                .get(0).path("id").asLong();
        data(postJson("/field-definition", token, map("code", "audit_format", "name", "格式", "nameEn", "Format",
                "dataType", "ENUM", "enable", true, "paramGroupId", dictionary, "defaultValue", "EMAIL")));

        var draft = draft("AUDIT_TEMPLATE");
        draft.put("fields", List.of("audit_format"));
        var saved = data(postJson(DEFINITIONS, token, draft));
        long id = saved.path("id").asLong();
        String path = DEFINITIONS + "/" + id;
        var created = latest(TODO_FLOW_DEFINE);
        assertEquals("SUCCESS", created.path("result").asString());
        assertEquals("1", created.path("actor").path("id").asString());
        var object = created.path("objects").get(0);
        assertEquals(OBJECT_TYPE, object.path("object").path("objectType").asString());
        assertEquals(Long.toString(id), object.path("object").path("objectId").asString());
        assertTrue(object.path("beforeData").isNull());
        assertEquals("Template", field(object, "afterData", "name").asString());
        assertEquals("audit_format", field(object, "afterData", "fields").get(0).asString());
        assertEquals("Review", field(object, "afterData", "steps").get(0).path("title").asString());

        draft.put("version", saved.path("version").asLong());
        draft.put("name", "Updated");
        draft.put("nodes", List.of(map("key", "review", "order", 1, "title", "Confirm",
                "roleCode", "ROLE_QA", "mode", "BROADCAST", "requiredFields", List.of("audit_format"))));
        var edited = data(patchJson(path, token, draft));
        var edit = latest(TODO_FLOW_DEFINE_EDIT).path("objects").get(0);
        assertEquals("Template", field(edit, "beforeData", "name").asString());
        assertEquals("Updated", field(edit, "afterData", "name").asString());
        assertEquals("audit_format", field(edit, "afterData", "steps").get(0).path("requiredFields").get(0).asString());

        draft.put("version", edited.path("version").asLong());
        var resaved = data(patchJson(path, token, draft));
        assertEquals("NO_CHANGE", latest(TODO_FLOW_DEFINE_EDIT).path("result").asString());

        var published = data(postJson(path + "/publish", token, map("version", resaved.path("version").asLong())));
        var publication = latest(TODO_FLOW_PUBLISH).path("objects").get(0);
        assertEquals("DRAFT", field(publication, "beforeData", "status").asString());
        assertEquals("PUBLISHED", field(publication, "afterData", "status").asString());
        assertTrue(field(publication, "beforeData", "fieldRules").isEmpty());
        var rules = field(publication, "afterData", "fieldRules");
        assertEquals("EMAIL", rules.get(0).path("config").path("defaultValue").asString());
        assertTrue(rules.get(0).path("options").valueStream().anyMatch(option -> "URL".equals(option.path("code").asString())));
        assertEquals(data(getJson(path, token)).path("publishedAt"), field(publication, "afterData", "publishedAt"));

        jdbc.update("UPDATE sys_param_item SET enable = 0 WHERE groupId = ? AND code = 'URL'", dictionary);
        data(postJson(path + "/retire", token, map("version", published.path("version").asLong())));
        var retirement = latest(TODO_FLOW_RETIRE).path("objects").get(0);
        assertEquals("PUBLISHED", field(retirement, "beforeData", "status").asString());
        assertEquals("RETIRED", field(retirement, "afterData", "status").asString());
        assertEquals(rules, field(retirement, "afterData", "fieldRules"));
        assertEquals(field(publication, "afterData", "publishedAt"), field(retirement, "afterData", "publishedAt"));

        var revision = data(postJson(DEFINITIONS, token, draft));
        var revisionChange = latest(TODO_FLOW_DEFINE).path("objects").get(0);
        assertNotEquals(id, revision.path("id").asLong());
        assertEquals(revision.path("id").asLong(), revisionChange.path("object").path("objectId").asLong());
        assertEquals(2, field(revisionChange, "afterData", "revision").asInt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedTemplateMutationsAreSearchableByTargetId() throws Exception {
        long id = 99999999;
        String path = DEFINITIONS + "/" + id;
        String token = loginAsAdmin();
        var payload = draft("AUDIT_MISSING_TEMPLATE");
        payload.put("version", 0);
        assertRejectedAndDeliver(patchJson(path, token, payload), id);
        assertRejectedAndDeliver(postJson(path + "/publish", token, map("version", 0)), id);
        assertRejectedAndDeliver(postJson(path + "/retire", token, map("version", 0)), id);

        var history = data(getJson("/audit/logs", token, "moduleCode", "TODO", "objectType", OBJECT_TYPE,
                "objectId", id, "result", "FAILURE")).path("pageData");
        assertTrue(history.valueStream().map(row -> row.path("operationCode").asString()).toList()
                .containsAll(List.of(TODO_FLOW_DEFINE_EDIT.code(), TODO_FLOW_PUBLISH.code(), TODO_FLOW_RETIRE.code())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void publishingRollsBackIfItsAuditCannotBeEnqueued() throws Exception {
        String token = loginAsAdmin();
        var saved = data(postJson(DEFINITIONS, token, draft("AUDIT_TEMPLATE_ROLLBACK")));
        long id = saved.path("id").asLong();
        String path = DEFINITIONS + "/" + id;
        long existing = jdbc.queryForObject("SELECT MAX(id) FROM sys_audit_outbox", Long.class);
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_template_audit "
                + "CHECK (operationCode <> 'workbench.flow.definition.publish' OR result <> 'SUCCESS' OR id <= " + existing + ")");
        try {
            body(postJson(path + "/publish", token, map("version", saved.path("version").asLong())), 503);
            assertEquals(saved, data(getJson(path, token)));
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_template_audit");
            jdbc.update("DELETE FROM wf_definition WHERE id = ?", id);
        }
    }

    private Map<String, Object> draft(String code) {
        return map("code", code, "name", "  Template  ", "fields", List.of(),
                "nodes", List.of(map("key", "review", "order", 1, "title", "Review", "roleCode", "ROLE_QA", "mode", "BROADCAST")));
    }

    private void assertRejectedAndDeliver(ResultActions request, long targetId) throws Exception {
        body(request, 404);
        String requestId = request.andReturn().getResponse().getHeader("X-Request-ID");
        long outboxId = jdbc.queryForObject("SELECT id FROM sys_audit_outbox WHERE requestId = ?", Long.class, requestId);
        var delivery = store.find(outboxId);
        var event = objectMapper.readTree(delivery.payload());
        assertEquals("FAILURE", event.path("result").asString());
        assertEquals("ATTEMPT", event.path("phase").asString());
        var target = event.path("objects").get(0);
        assertEquals("REFERENCE", target.path("entryType").asString());
        assertEquals(Long.toString(targetId), target.path("object").path("objectId").asString());
        var lease = store.claim(delivery);
        assertNotNull(lease);
        store.consume(lease);
    }

    private JsonNode latest(AuditOperation operation) {
        return objectMapper.readTree(jdbcClient.sql(
                        "SELECT payload FROM sys_audit_outbox WHERE operationCode = ? ORDER BY id DESC LIMIT 1")
                .param(operation.code()).query(String.class).single());
    }

    private static JsonNode field(JsonNode change, String side, String path) {
        return change.path(side).path(path).path("value");
    }
}
