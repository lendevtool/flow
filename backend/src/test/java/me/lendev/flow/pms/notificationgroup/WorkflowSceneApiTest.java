package me.lendev.flow.pms.notificationgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

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

@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:workflow-scene-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowSceneApiTest extends ApiTestBase {

    private static final String SCENES = "/notification-groups/scenes";
    private static final String CODE = "test.workflow.scene";
    private static final String PATH = SCENES + "/workflow/" + CODE;
    private static final String UPDATE_CODE = "system.notification.workflow.scene.update";
    private static final String MAIL_ACCOUNT = "notification_scene_api_mail";

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sys_notification_scene WHERE scene_code=?", CODE);
        jdbc.update("DELETE FROM sys_mail_template WHERE accountId IN (SELECT id FROM sys_mail_account WHERE code=?)",
                MAIL_ACCOUNT);
        jdbc.update("DELETE FROM sys_mail_account WHERE code=?", MAIL_ACCOUNT);
    }

    @Test
    void versionedExtraSceneCanBePausedAndEnabledAndTemplateValuesStayOutOfAudit() throws Exception {
        String token = loginAsAdmin();
        var createPath = SCENES + "/workflow";
        body(postJson(createPath, token, request(0, true, "{{ unsupported.name }}")), 400);
        var created = data(postJson(createPath, token, request(0, true, "进度 {{ node.name }}")));
        assertEquals("WORKFLOW_EXTRA", created.path("sceneType").asString());
        assertEquals(0, created.path("version").asLong());
        assertEquals("SCENE_CODE_CONFLICT", body(postJson(createPath, token,
                request(0, true, "进度")), 409).path("data").path("reason").asString());
        assertEquals(0, data(getJson(PATH + "/usages", token)).path("runningInstanceCount").asLong());

        assertFalse(data(update(PATH, token, request(0, false, "进度 {{ node.name }}"))).path("enabled").asBoolean());
        body(update(PATH, token, request(0, true, "进度")), 409);
        var enabled = data(update(PATH, token, request(1, true, "这里是受保护的模板文本 {{ node.name }}")));
        assertTrue(enabled.path("enabled").asBoolean());
        assertEquals(2, enabled.path("version").asLong());
        assertEquals(2L, jdbc.queryForObject("SELECT activation_version FROM sys_notification_scene WHERE scene_code=?",
                Long.class, CODE));
        String audit = jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1",
                String.class, UPDATE_CODE);
        assertEquals("SUCCESS", objectMapper.readTree(audit).path("result").asString());
        assertTrue(audit.contains("titleTemplate"));
        assertFalse(audit.contains("受保护的模板文本"));

        assertEquals(2, data(update(PATH, token, request(2, true, "这里是受保护的模板文本 {{ node.name }}")))
                .path("version").asLong());
    }

    @Test
    void systemSceneCannotBeDisabledOrBoundToGroups() throws Exception {
        String token = loginAsAdmin();
        String code = "workflow.task.assigned";
        var system = data(getJson(SCENES + "/" + code, token));
        assertEquals("WORKFLOW_SYSTEM", system.path("sceneType").asString());
        body(update(SCENES + "/workflow/" + code, token, map("expectedVersion", 0, "enabled", false)), 400);
        body(update(SCENES + "/" + code + "/groups", token,
                map("expectedVersion", 0, "groupIds", List.of())), 400);
        assertEquals(0, data(update(SCENES + "/workflow/" + code, token, map("expectedVersion", 0,
                "titleTemplate", "客户端不可改的系统内容"))).path("version").asLong());
    }

    @Test
    void mailPickerAndSceneWritesValidateThePermittedVariablesAndAccountState() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("""
                INSERT INTO sys_mail_account (code,name,provider,mailboxAddress,configJson,credentialCiphertext,
                    createdBy,createdAt,updatedBy,updatedAt)
                VALUES (?,'测试邮件账户','GRAPH','test@example.com','{}','omitted-fixture',
                    1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                """, MAIL_ACCOUNT);
        long account = jdbc.queryForObject("SELECT id FROM sys_mail_account WHERE code=?", Long.class, MAIL_ACCOUNT);
        long safe = insertTemplate(account, "notice-valid", "{{recipient.name}} {{node.name}}");
        long privateVariable = insertTemplate(account, "notice-invalid", "{{recipient.email}}");
        var options = data(getJson(SCENES + "/workflow/mail-templates", token));
        assertTrue(options.valueStream().anyMatch(t -> t.path("id").asLong() == safe && t.path("enable").asBoolean()));
        assertTrue(options.valueStream().anyMatch(t -> t.path("id").asLong() == privateVariable
                && !t.path("enable").asBoolean()));
        assertFalse(options.toString().contains("recipient."));

        var request = request(0, true, "流程进度");
        request.put("mailTemplateId", privateVariable);
        body(postJson(SCENES + "/workflow", token, request), 400);
        request.put("mailTemplateId", safe);
        assertEquals(safe, data(postJson(SCENES + "/workflow", token, request)).path("mailTemplateId").asLong());
        jdbc.update("UPDATE sys_mail_account SET enable=FALSE WHERE id=?", account);
        request.put("enabled", false);
        assertFalse(data(update(PATH, token, request)).path("enabled").asBoolean());
        request.put("enabled", true);
        request.put("expectedVersion", 1);
        body(update(PATH, token, request), 400);
        request.remove("mailTemplateId");
        assertTrue(data(update(PATH, token, request)).path("mailTemplateId").isNull());
        body(update(SCENES + "/workflow/test.missing.scene", token, map("expectedVersion", 0)), 404);
    }

    private long insertTemplate(long account, String code, String subject) {
        jdbc.update("""
                INSERT INTO sys_mail_template (code,name,accountId,subjectTemplate,bodyTemplate,
                    createdBy,createdAt,updatedBy,updatedAt)
                VALUES (?,?,?,?,'正文',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                """, code, code, account, subject);
        return jdbc.queryForObject("SELECT id FROM sys_mail_template WHERE code=?", Long.class, code);
    }

    private Map<String, Object> request(long version, boolean enabled, String title) {
        return map("sceneCode", CODE, "nameCn", "测试流程场景", "titleTemplate", title,
                "enabled", enabled, "expectedVersion", version);
    }

    private ResultActions update(String path, String token, Object body) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(body)));
    }
}
