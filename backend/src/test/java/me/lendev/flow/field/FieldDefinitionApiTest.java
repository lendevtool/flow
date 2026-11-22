package me.lendev.flow.field;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class FieldDefinitionApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void explainsOnlyTheConfiguredCharacterClassesWhenTestingOrSavingADefault() throws Exception {
        String token = loginAsAdmin();
        var definition = map("code", "test_characters", "name", "编码", "nameEn", "Code",
                "dataType", "TEXT", "enable", true, "textCharLimitEnabled", true,
                "textAllowEnglish", true, "textAllowDigits", true);
        long id = data(postJson("/field-definition", token, definition)).asLong();
        var result = data(postJson("/field-definition/" + id + "/validate", token, map("value", "中1")));
        assertEquals("error.field.validation.text.characters", result.path("code").asString());
        String message = "第 1 个字符「中」属于汉字。此字段只允许英文字母（A-Z、a-z）、数字（0-9）。";
        assertEquals(message, result.path("message").asString());
        definition.put("defaultValue", "中1");
        assertEquals(message, body(update(id, token, definition), 400).path("message").asString());
    }

    @Test
    void testsTheLatestSavedRulesAndLocalizesFailuresWithoutWriting() throws Exception {
        String token = loginAsAdmin();
        var definition = map("code", "test_count", "name", "数量", "nameEn", "Count",
                "dataType", "INTEGER", "enable", false, "required", true, "integerMax", "10", "defaultValue", "1");
        long id = data(postJson("/field-definition", token, definition)).asLong();
        String url = "/field-definition/" + id;
        var before = data(getJson(url, token));
        var valid = data(postJson(url + "/validate", token, map("value", "10")));
        assertTrue(valid.path("valid").asBoolean());
        assertTrue(valid.path("code").isNull());
        var required = data(postJson(url + "/validate", token, map()));
        assertEquals("error.field.validation.required", required.path("code").asString());
        assertEquals(before, data(getJson(url, token)));

        definition.put("integerMax", "8");
        data(update(id, token, definition));
        var failure = data(mockMvc.perform(post(url + "/validate").header("Authorization", bearer(token))
                .header("Accept-Language", "en-US").contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("value", "10")))));
        assertFalse(failure.path("valid").asBoolean());
        assertEquals("error.field.validation.max", failure.path("code").asString());
        assertEquals("Value must be at most 8", failure.path("message").asString());

        data(deleteJson(url, token));
        body(postJson(url + "/validate", token, map("value", "10")), 400);
    }

    @Test
    void testsValuesAgainstCurrentDictionaryWithoutReusingTheHistoricalDefault() throws Exception {
        String token = loginAsAdmin();
        long group = data(getJson("/field-definition/dictionaries", token, "keyword", "FIELD_TEXT_FORMAT"))
                .get(0).path("id").asLong();
        var definition = map("code", "test_format", "name", "格式", "nameEn", "Format",
                "dataType", "ENUM", "enable", true, "paramGroupId", group, "defaultValue", "EMAIL");
        long id = data(postJson("/field-definition", token, definition)).asLong();
        String url = "/field-definition/" + id + "/validate";
        assertTrue(data(postJson(url, token, map("value", "EMAIL"))).path("valid").asBoolean());
        jdbc.sql("UPDATE sys_param_item SET enable = 0 WHERE groupId = ? AND code = 'EMAIL'").param(group).update();
        var invalidOption = data(postJson(url, token, map("value", "EMAIL")));
        assertEquals("error.field.validation.enum.option", invalidOption.path("code").asString());
        assertTrue(invalidOption.path("message").asString().contains("可用编码示例为 URL"));
        assertFalse(invalidOption.path("message").asString().contains("EMAIL"));
        assertTrue(data(postJson(url, token, map("value", "URL"))).path("valid").asBoolean());
        jdbc.sql("UPDATE sys_param_group SET enable = 0 WHERE id = ?").param(group).update();
        var disabledDictionary = data(postJson(url, token, map("value", "URL")));
        assertEquals("error.field.validation.dictionary.disabled", disabledDictionary.path("code").asString());
        assertTrue(disabledDictionary.path("message").asString().contains("FIELD_TEXT_FORMAT"));
        assertTrue(data(postJson(url, token, map("value", null))).path("valid").asBoolean());
    }

    @Test
    void requiresOnlyTheFieldMenuPermissionToTestAValue() throws Exception {
        String token = loginAsAdmin();
        long id = data(postJson("/field-definition", token, map("code", "test_flag", "name", "标记", "nameEn", "Flag",
                "dataType", "BOOLEAN", "enable", true, "required", true))).asLong();
        String url = "/field-definition/" + id + "/validate";
        body(postJson(url, map("value", false)), 401);
        data(postJson("/auth/current-role/switch/ROLE_QA", token, map()));
        code(postJson(url, token, map("value", false)), 11003);
        jdbc.sql("""
                INSERT INTO role_permissions_permission (roleId, permissionId)
                SELECT 2, id FROM permission WHERE code = 'FieldMgt'
                """).update();
        assertTrue(data(postJson(url, token, map("value", false))).path("valid").asBoolean());
    }

    @Test
    void managesFieldsAndClearsOldTypeRules() throws Exception {
        String token = loginAsAdmin();
        var definition = map("code", "project_reference", "name", "项目编号", "nameEn", "Project Reference",
                "dataType", "TEXT", "enable", true, "textMinLength", 2, "textMaxLength", 12,
                "textCharLimitEnabled", true, "textAllowEnglish", true, "textAllowDigits", true,
                "defaultValue", "A01");
        long id = data(postJson("/field-definition", token, definition)).asLong();
        body(postJson("/field-definition", token, definition), 400);
        var row = data(getJson("/field-definition/" + id, token));
        assertEquals("A01", row.path("config").path("defaultValue").asString());
        assertTrue(row.path("createdBy").asLong() > 0);
        assertEquals(1, data(getJson("/field-definition", token, "keyword", "Reference",
                "dataType", "TEXT", "enable", true)).path("total").asInt());

        definition.put("dataType", "DECIMAL");
        body(update(id, token, definition), 400);
        var replacement = map("code", "project_reference", "name", "项目金额", "nameEn", "Project Amount",
                "dataType", "DECIMAL", "enable", true, "numberPositiveOnly", true,
                "decimalMax", "99999999999999999999.123456789012345678", "defaultValue", "0.01");
        data(update(id, token, replacement));
        var config = data(getJson("/field-definition/" + id, token)).path("config");
        assertEquals("DECIMAL", config.path("dataType").asString());
        assertEquals("99999999999999999999.123456789012345678", config.path("decimalMax").asString());
        assertEquals("0.01", config.path("defaultValue").asString());
        assertTrue(config.path("textMinLength").isNull());
        assertFalse(config.path("textAllowEnglish").asBoolean());
        assertTrue(config.path("numberPositiveOnly").asBoolean());
        replacement.put("code", "other");
        body(update(id, token, replacement), 400);

        data(deleteJson("/field-definition/" + id, token));
        body(getJson("/field-definition/" + id, token), 400);
    }

    @Test
    void loadsSeededMetadataAndValidatesDictionarySelection() throws Exception {
        String token = loginAsAdmin();
        JsonNode metadata = data(getJson("/field-definition/metadata", token));
        assertEquals(9, metadata.path("types").size());
        assertEquals(2, metadata.path("formats").size());
        assertEquals(7, metadata.path("characters").size());
        var groups = data(getJson("/field-definition/dictionaries", token, "keyword", "FIELD_TEXT_FORMAT"));
        assertEquals(1, groups.size());
        long group = groups.get(0).path("id").asLong();
        assertEquals(2, data(getJson("/field-definition/dictionaries/" + group + "/options", token)).size());

        var definition = map("code", "format_choice", "name", "格式", "nameEn", "Format",
                "dataType", "ENUM", "enable", true, "paramGroupId", group, "defaultValue", "EMAIL");
        long id = data(postJson("/field-definition", token, definition)).asLong();
        jdbc.sql("UPDATE sys_param_item SET enable = 0 WHERE groupId = ? AND code = 'EMAIL'").param(group).update();
        definition.put("nameEn", "Format Choice");
        data(update(id, token, definition));
        definition.put("code", "new_format_choice");
        body(postJson("/field-definition", token, definition), 400);
        definition.put("defaultValue", "URL");
        data(postJson("/field-definition", token, definition));
    }

    private org.springframework.test.web.servlet.ResultActions update(long id, String token, Object data) throws Exception {
        return mockMvc.perform(put("/field-definition/{id}", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(data)));
    }
}
