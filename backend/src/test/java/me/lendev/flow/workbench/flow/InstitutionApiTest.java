package me.lendev.flow.workbench.flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.support.ApiTestBase;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql("/db/seed/workflow-examples.sql")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:institutions;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "workflow.business.delivery.poll-ms=600000"
})
class InstitutionApiTest extends ApiTestBase {

    private static final String ROOT = "/institutions";
    private static final String NAME_EN = "institution_name_en";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkflowBusinessDeliveryWorker delivery;
    @Autowired
    private WorkflowBusinessRegistry businesses;

    @Test
    void directEditRecordsAuditAndApprovalPreservesUnrelatedEditsAndReportsConflict() throws Exception {
        String token = loginAsAdmin();
        var options = data(getJson(ROOT + "/options", token));
        long bank = option(options.path("types"), "BANK");
        long china = option(options.path("countries"), "CN");
        var form = map("code", "TEST-BANK", "nameZh", "测试银行", "nameEn", "Original Bank",
                "typeItemId", bank, "countryRegionItemId", china);
        var created = data(postJson(ROOT, token, form));
        String path = ROOT + "/" + created.path("id").asLong();
        assertEquals(created.path("id"), data(getJson(ROOT, token, "keyword", "TEST-BANK",
                "typeItemId", bank)).path("pageData").get(0).path("id"));
        assertEquals(0, data(getJson(ROOT, token, "keyword", "%")).path("total").asInt());
        body(postJson(ROOT, token, form), 409);
        var edits = map("code", "TEST-BANK", "nameZh", "测试银行", "nameEn", "Original Bank",
                "typeItemId", bank, "countryRegionItemId", china, "version", 0, "remark", "Direct edit");
        var current = data(putJson(path, token, edits));
        assertEquals(1, current.path("version").asLong());
        assertEquals("Direct edit", current.path("remark").asString());
        body(putJson(path, token, edits), 409);

        long templateId = installApproval(token);
        assertTrue(options.path("approvalOptions").isNull());
        assertTrue(data(getJson(ROOT + "/options", token)).path("approvalOptions").path("fields").isArray());
        for (JsonNode definition : data(getJson("/workflow/catalog", token))) {
            assertNotEquals("INSTITUTION_CHANGE", definition.path("code").asString());
        }
        assertEquals(400, body(postJson("/workflow/instances", token,
                map("definitionId", templateId, "requestKey", "standalone", "title", "Standalone")), 400)
                .path("code").asInt());
        var change = request(token, path, current, "Approved Bank");
        assertEquals("Original Bank", change.path("baseValues").path(NAME_EN).asString());
        assertEquals("Original Bank", data(getJson(path, token)).path("nameEn").asString());
        body(postJson(path + "/changes", token, map("version", current.path("version").asLong(),
                "reason", "重复申请", "fields", map(NAME_EN, "Another"))), 409);
        var inReview = data(getJson(flowPath(change), token));
        assertEquals(NAME_EN, inReview.path("tasks").get(0).path("editableFields").get(0).asString());
        assertEquals(1, inReview.path("tasks").get(0).path("editableFields").size());
        assertEquals(400, body(postJson(flowPath(change) + "/actions/submit", token,
                action(inReview, "outside-scope", map("institution_name_zh", "其他名称"))), 400).path("code").asInt());
        edits.put("version", current.path("version").asLong());
        edits.put("remark", "Updated while pending");
        data(putJson(path, token, edits));
        approve(token, change);
        var applied = data(getJson(path, token));
        assertEquals("Approved Bank", applied.path("nameEn").asString());
        assertEquals("Updated while pending", applied.path("remark").asString());
        assertEquals("APPLIED", result(token, path, change).path("status").asString());
        replay(change);
        assertEquals(applied.path("version"), data(getJson(path, token)).path("version"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                WHERE operationCode = 'business.institution.apply' AND payload LIKE '%WORKFLOW_INSTANCE%'
                """, Integer.class));

        var cleared = verifyConflictClearingAndCancellation(token, path, applied, edits);
        data(putJson(path + "/status", token, map("enabled", false, "version", cleared.path("version").asLong())));
        assertFalse(data(getJson(path, token)).path("enabled").asBoolean());
        assertEquals(1, data(getJson(ROOT, token, "enabled", false)).path("total").asInt());
        assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                WHERE moduleCode = 'INSTITUTION' AND operationCode = 'business.institution.edit' AND result = 'SUCCESS'
                """, Integer.class));
    }

    private JsonNode verifyConflictClearingAndCancellation(String token, String path, JsonNode applied,
                                                           Map<String, Object> edits) throws Exception {
        var second = request(token, path, applied, "Another Proposed Name");
        edits.put("version", applied.path("version").asLong());
        edits.put("nameEn", "Direct Name");
        data(putJson(path, token, edits));
        approve(token, second);
        assertEquals("Direct Name", data(getJson(path, token)).path("nameEn").asString());
        assertEquals("CONFLICT", result(token, path, second).path("status").asString());

        var clearing = request(token, path, data(getJson(path, token)), null);
        approve(token, clearing);
        var cleared = data(getJson(path, token));
        assertTrue(cleared.path("nameEn").isNull());
        assertEquals("APPLIED", result(token, path, clearing).path("status").asString());

        var cancelled = request(token, path, cleared, "Cancelled Name");
        String flow = flowPath(cancelled);
        var running = data(getJson(flow, token));
        data(postJson(flow + "/actions/cancel", token, map("requestKey", "cancel",
                "version", running.path("instance").path("version").asLong(), "reason", "Cancel test")));
        delivery.poll();
        assertEquals("CANCELLED", result(token, path, cancelled).path("status").asString());
        assertTrue(data(getJson(path, token)).path("nameEn").isNull());
        return cleared;
    }

    private ResultActions putJson(String path, String token, Object values) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token))
                .contentType("application/json").content(writeJson(values)));
    }

    private static long option(JsonNode options, String code) {
        for (JsonNode item : options) {
            if (code.equals(item.path("code").asString())) {
                return item.path("id").asLong();
            }
        }
        throw new AssertionError("Missing option " + code);
    }

    private long installApproval(String token) throws Exception {
        jdbc.update("""
                INSERT INTO user_roles_role(userId, roleId)
                SELECT 1,id FROM role WHERE code='WF_DEMO_REQUESTER'
                AND NOT EXISTS (SELECT 1 FROM user_roles_role u WHERE u.roleId=role.id AND u.userId=1)
                """);
        jdbc.update("""
                INSERT INTO wf_role_handler (userId,roleId,kind,primaryRoleId)
                SELECT 1,id,'PRIMARY',id FROM role WHERE code='WF_DEMO_REQUESTER'
                AND NOT EXISTS (SELECT 1 FROM wf_role_handler h WHERE h.roleId=role.id AND h.userId=1)
                """);
        var template = data(postJson("/workflow/definitions", token,
                map("code", "INSTITUTION_CHANGE", "name", "机构资料变更", "fields",
                        List.of("institution_code", NAME_EN, "institution_name_zh", "institution_change_reason"),
                        "nodes", List.of(map("key", "review", "order", 1, "title", "Review",
                                "roleCode", "WF_DEMO_REQUESTER", "mode", "PRIMARY", "editableFields", List.of(NAME_EN, "institution_name_zh"))))));
        long id = template.path("id").asLong();
        data(postJson("/workflow/definitions/" + id + "/publish", token,
                map("version", template.path("version").asLong())));
        return id;
    }

    private JsonNode request(String token, String path, JsonNode current, String proposed) throws Exception {
        return data(postJson(path + "/changes", token, map("version", current.path("version").asLong(),
                "reason", "维护英文名称", "fields", map(NAME_EN, proposed))));
    }

    private JsonNode result(String token, String path, JsonNode change) throws Exception {
        return data(getJson(path + "/changes/" + change.path("id").asLong(), token));
    }

    private static String flowPath(JsonNode change) {
        return "/workflow/instances/" + change.path("flowInstanceId").asLong();
    }

    private Object action(JsonNode detail, String key, Object fields) {
        return map("requestKey", key, "version", detail.path("instance").path("version").asLong(),
                "todoId", detail.path("tasks").get(0).path("todoId").asLong(), "fields", fields);
    }

    private void approve(String token, JsonNode change) throws Exception {
        String flow = flowPath(change);
        var detail = data(getJson(flow, token));
        assertEquals("DONE", data(postJson(flow + "/actions/submit", token, action(detail, "approve", map())))
                .path("instance").path("status").asString());
        delivery.poll();
    }

    private void replay(JsonNode change) throws Exception {
        var payload = jdbc.queryForObject("""
                SELECT d.payloadJson FROM wf_business_delivery d
                 JOIN wf_flow_event e ON e.id = d.eventId WHERE e.flowInstanceId = ?
                """, String.class, change.path("flowInstanceId").asLong());
        try (var scope = AuditSystemContext.open("机构回写测试")) {
            businesses.require("institution").onEvent(objectMapper.readValue(payload, Event.class));
        }
    }
}
