package me.lendev.flow.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AuthPolicyApiTest extends ApiTestBase {

    private static final String PATH = "/system-policy/auth-security";
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void savesFieldChangesWithAuditAndRejectsStaleRevision() throws Exception {
        String admin = loginAsAdmin();
        JsonNode before = data(getJson(PATH, admin));
        long revision = before.path("revision").asLong();
        var config = (ObjectNode) before.path("config");
        ((ObjectNode) config.path("password")).put("minLength", 10);
        var saved = data(update(admin, revision, config));
        assertEquals(revision + 1, saved.path("revision").asLong());
        assertEquals(10, saved.path("config").path("password").path("minLength").asInt());
        assertEquals(12, saved.path("defaults").path("password").path("minLength").asInt());

        String payload = jdbc.queryForObject("""
                SELECT payload FROM sys_audit_outbox WHERE operationCode='system.policy.update'
                ORDER BY id DESC LIMIT 1
                """, String.class);
        var fields = objectMapper.readTree(payload).path("objects").get(0).path("changes");
        assertEquals(1, fields.size());
        var field = fields.get(0);
        assertEquals("password.minLength", field.path("path").asString());
        assertEquals(12, field.path("before").path("value").asInt());
        assertEquals(10, field.path("after").path("value").asInt());
        body(update(admin, revision, config), 409);

        var invalid = config.deepCopy();
        ((ObjectNode) invalid.path("session")).put("idleTimeoutSeconds", 40000);
        body(update(admin, revision + 1, invalid), 400);
        assertEquals(revision + 1, data(getJson(PATH, admin)).path("revision").asLong());

        var reset = data(postJson(PATH + "/reset", admin, map("revision", revision + 1)));
        assertEquals(revision + 2, reset.path("revision").asLong());
        assertEquals(reset.path("config"), reset.path("defaults"));
    }

    @Test
    void newPolicyControlsSubsequentLoginsAndNewPasswords() throws Exception {
        String admin = loginAsAdmin();
        var before = data(getJson(PATH, admin));
        var config = (ObjectNode) before.path("config");
        ((ObjectNode) config.path("captcha")).put("required", false);
        ((ObjectNode) config.path("session")).put("allowConcurrent", false);
        var passwords = (ObjectNode) config.path("password");
        passwords.put("minLength", 8);
        passwords.put("rejectUsername", false);
        passwords.putArray("requiredCharacterTypes").add("digit").add("uppercase");
        var saved = data(update(admin, before.path("revision").asLong(), config));
        assertEquals(List.of("uppercase", "digit"), objectMapper.convertValue(
                saved.path("config").path("password").path("requiredCharacterTypes"), List.class));
        assertFalse(data(getJson("/auth/login/options", null)).path("captchaRequired").asBoolean());
        mockMvc.perform(get("/auth/captcha"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("X-Captcha-Key"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        var login = map("username", ADMIN, "password", ADMIN_PASSWORD);
        String first = data(postJson("/auth/login", login)).path("accessToken").asString();
        String second = data(postJson("/auth/login", login)).path("accessToken").asString();
        body(getJson("/user/detail", first), 401);
        data(getJson("/user/detail", second));
        body(getJson("/auth/password/rules", null), 401);
        var rules = data(getJson("/auth/password/rules", second)
                .andExpect(header().string("Cache-Control", "no-store")));
        assertEquals(8, rules.path("minLength").asInt());
        assertEquals(20, rules.path("maxLength").asInt());
        assertEquals(saved.path("config").path("password").path("requiredCharacterTypes"),
                rules.path("requiredCharacterTypes"));
        assertFalse(rules.path("rejectUsername").asBoolean());
        assertTrue(rules.path("rejectCurrent").asBoolean());
        data(postJson("/auth/password", second, map("oldPassword", ADMIN_PASSWORD, "newPassword", "ABCDEFG1")));
        data(postJson("/auth/login", map("username", ADMIN, "password", "ABCDEFG1")));
    }

    @Test
    void enablingPasswordExpiryInitializesOnlyPermanentPasswordsWithoutTimestamps() throws Exception {
        String admin = loginAsAdmin();
        LocalDateTime previousChange = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("""
                INSERT INTO `user` (username, password, passwordChangedAt, temporaryPasswordExpiresAt)
                VALUES ('legacy-password', 'unused', NULL, NULL),
                       ('dated-password', 'unused', ?, NULL),
                       ('temporary-password', 'unused', NULL, ?)
                """, previousChange, previousChange.plusDays(1));

        var before = data(getJson(PATH, admin));
        var config = (ObjectNode) before.path("config");
        ((ObjectNode) config.path("password")).put("maxAgeSeconds", 86400);
        data(update(admin, before.path("revision").asLong(), config));
        LocalDateTime policyUpdatedAt = jdbcClient.sql("SELECT updated_at FROM sys_system_policy WHERE policy_code = ?")
                .param(AuthPolicy.CODE).query(LocalDateTime.class).single();

        String passwordChangedAt = "SELECT passwordChangedAt FROM `user` WHERE username = ?";
        assertEquals(policyUpdatedAt, jdbc.queryForObject(passwordChangedAt, LocalDateTime.class, "legacy-password"));
        assertEquals(previousChange, jdbc.queryForObject(passwordChangedAt, LocalDateTime.class, "dated-password"));
        assertNull(jdbc.queryForObject(passwordChangedAt, LocalDateTime.class, "temporary-password"));
    }

    @Test
    void rejectsNonAdministratorAndDoesNotExtendIdleTimeForRefreshOrPolling() throws Exception {
        String admin = loginAsAdmin();
        String token = data(postJson("/auth/current-role/switch/ROLE_QA", admin, Map.of())).path("accessToken").asString();
        code(getJson(PATH, token), 11003);

        String activity = "flow:auth:activity:" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(token);
        assertTrue(redis.expire(activity, Duration.ofSeconds(12)));
        data(getJson("/auth/refresh/token", token));
        data(mockMvc.perform(get("/user/detail").header("Authorization", bearer(token)).header("X-Session-Passive", "true")));
        assertTrue(redis.getExpire(activity, TimeUnit.SECONDS) <= 12);
        data(getJson("/user/detail", token));
        assertTrue(redis.getExpire(activity, TimeUnit.SECONDS) > 12);
        redis.opsForHash().put(activity, "deadline", "1");
        body(getJson("/user/detail", token), 401);
    }

    private org.springframework.test.web.servlet.ResultActions update(String token, long revision, JsonNode config) throws Exception {
        return mockMvc.perform(put(PATH).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("revision", revision, "config", config))));
    }
}
