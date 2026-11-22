package me.lendev.flow.common.auth.mfa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import cn.hutool.core.codec.Base32;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flow_mfa_rotation;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "security.mfa.key-storage-encryption-key=QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
})
class MfaKeyRotationApiTest extends ApiTestBase {

    private static final String POLICY = "/system-policy/auth-security";
    private static final String ROTATION = POLICY + "/mfa-rotation";
    private static final String MFA = "/auth/mfa";
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private MfaRotationWorker worker;
    @Autowired
    private MfaRotationStore store;
    @Autowired
    private MfaSecrets secrets;
    @Autowired
    private MfaKeyStore keys;
    @Autowired
    private MfaKeyWrapping wrapping;

    @BeforeEach
    void preparePublishedVersions() {
        Instant now = Instant.now();
        var first = MfaSecrets.decodeKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        var second = MfaSecrets.decodeKey("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=");
        keys.insert("v1", wrapping.wrap("v1", first), now);
        keys.insert("v2", wrapping.wrap("v2", second), now);
        keys.publish("v2", now);
        store.activate(store.state(true), "v1", now);
        redis.delete("flow:auth:mfa:attempts:1");
    }

    @Test
    void reencryptsExistingBindingsWithoutChangingAuthenticatorOrRecoveryState() throws Exception {
        String admin = loginAsAdmin();
        var setup = data(postJson(MFA + "/setup", admin, map()));
        byte[] seed = Base32.decode(setup.path("setupKey").asString());
        var enrolled = data(postJson(MFA + "/setup/confirm", admin, map(
                "ticket", setup.path("ticket").asString(), "code", currentCode(seed))));
        var original = storedBinding();
        assertEquals("v1", original.get("key_version"));
        String token = loginWithCode(enrolled.path("recoveryCodes").get(0).asString());
        var before = storedBinding();

        var start = data(postJson(ROTATION + "/start", token, map()));
        assertEquals("v2", start.path("activeKeyId").asString());
        assertEquals("RUNNING", start.path("status").asString());
        assertEquals(1, start.path("pendingBindings").asLong());
        worker.tick();

        var done = data(getJson(ROTATION, token));
        assertEquals("COMPLETED", done.path("status").asString());
        assertEquals(0, done.path("pendingBindings").asLong());
        assertFalse(done.path("lastCompletedAt").isNull());
        var after = storedBinding();
        assertEquals(before.get("binding_id"), after.get("binding_id"));
        assertEquals(before.get("last_used_step"), after.get("last_used_step"));
        assertEquals(before.get("recovery_hashes"), after.get("recovery_hashes"));
        assertEquals("v2", after.get("key_version"));
        assertArrayEquals(seed, secrets.open(1, (String) after.get("secret_ciphertext")));
        assertNotEquals(before.get("secret_ciphertext"), after.get("secret_ciphertext"));

        long nextStep = ((Number) after.get("last_used_step")).longValue() + 1;
        data(getJson("/user/detail", loginWithCode(Totp.code(seed, nextStep))));
        String audit = jdbc.queryForObject("""
                SELECT payload FROM sys_audit_outbox
                WHERE operationCode='system.auth.mfa.rotation.complete' ORDER BY id DESC LIMIT 1
                """, String.class);
        assertTrue(audit.contains("COMPLETED"));
        assertFalse(audit.contains((String) after.get("secret_ciphertext")));
        assertFalse(audit.contains(setup.path("setupKey").asString()));
    }

    @Test
    void dueRotationRunsWithMfaGlobalOffAndOlderEnrollmentStillConfirms() throws Exception {
        String admin = loginAsAdmin();
        var setup = data(postJson(MFA + "/setup", admin, map()));
        jdbc.update("UPDATE sys_mfa_key_rotation SET active_since = ? WHERE id=1",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(366));
        var due = data(getJson(ROTATION, admin));
        assertTrue(due.path("overdue").asBoolean());
        assertTrue(due.path("reminderDue").asBoolean());
        worker.tick();

        assertEquals("v2", data(getJson(ROTATION, admin)).path("activeKeyId").asString());
        assertFalse(data(getJson(POLICY, admin)).path("config").path("mfa").path("required").asBoolean());
        var seed = Base32.decode(setup.path("setupKey").asString());
        data(postJson(MFA + "/setup/confirm", admin, map(
                "ticket", setup.path("ticket").asString(), "code", currentCode(seed))));
        assertEquals("v2", storedBinding().get("key_version"));
    }

    @Test
    void failedBatchCanResumeAndStaleMigrationDoesNotOverwriteRebinding() throws Exception {
        String admin = loginAsAdmin();
        jdbc.update("""
                INSERT INTO sys_user_mfa
                    (user_id, binding_id, personal_enabled, secret_ciphertext, key_version, last_used_step, recovery_hashes)
                VALUES (1, 'test', FALSE, 'invalid', 'v1', 1, '[]')
                """);
        data(postJson(ROTATION + "/start", admin, map()));
        worker.tick();
        assertEquals("FAILED", data(getJson(ROTATION, admin)).path("status").asString());
        byte[] seed = secrets.newSecret();
        String fixed = secrets.protect(1, "v1", seed);
        jdbc.update("UPDATE sys_user_mfa SET secret_ciphertext=? WHERE user_id=1", fixed);
        data(postJson(ROTATION + "/start", admin, map()));
        var stale = store.batch("v2").getFirst();

        byte[] replacement = secrets.newSecret();
        String rebound = secrets.protect(1, "v2", replacement);
        jdbc.update("UPDATE sys_user_mfa SET binding_id='new', key_version='v2', secret_ciphertext=? WHERE user_id=1", rebound);
        store.rewrite(stale, "v2", secrets.protect(1, "v2", seed));
        worker.tick();
        assertEquals("COMPLETED", data(getJson(ROTATION, admin)).path("status").asString());
        assertEquals(rebound, storedBinding().get("secret_ciphertext"));
        body(postJson(ROTATION + "/start", admin, map()), 400);
    }

    @Test
    void rotationPolicyValidatesReminderAndRequiresSuperAdmin() throws Exception {
        String admin = loginAsAdmin();
        var view = data(getJson(POLICY, admin));
        var mfa = (ObjectNode) view.path("config").path("mfa");
        assertEquals(365, mfa.path("keyRotationDays").asInt());
        assertEquals(30, mfa.path("keyReminderDays").asInt());
        mfa.put("keyRotationDays", 30);
        body(savePolicy(admin, view), 400);
        mfa.put("keyReminderDays", 10);
        data(savePolicy(admin, view));
        jdbc.update("UPDATE sys_mfa_key_rotation SET active_since = ? WHERE id=1",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(21));
        var warning = data(getJson(ROTATION, admin));
        assertTrue(warning.path("reminderDue").asBoolean());
        assertFalse(warning.path("overdue").asBoolean());

        var qa = data(postJson("/auth/current-role/switch/ROLE_QA", admin, map())).path("accessToken").asString();
        code(getJson(ROTATION, qa), 11003);
        code(postJson(ROTATION + "/start", qa, map()), 11003);
    }

    private org.springframework.test.web.servlet.ResultActions savePolicy(String admin, JsonNode view) throws Exception {
        return mockMvc.perform(put(POLICY).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("revision", view.path("revision").asLong(), "config", view.path("config")))));
    }

    private String loginWithCode(String code) throws Exception {
        var captcha = captchaService.create();
        var login = data(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD,
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())));
        return data(postJson(MFA + "/verify", map("ticket", login.path("mfaChallenge").path("ticket").asString(), "code", code)))
                .path("accessToken").asString();
    }

    private java.util.Map<String, Object> storedBinding() {
        return jdbc.queryForMap("""
                SELECT binding_id, key_version, last_used_step, CAST(recovery_hashes AS VARCHAR) AS recovery_hashes,
                    secret_ciphertext FROM sys_user_mfa WHERE user_id=1
                """);
    }

    private static String currentCode(byte[] seed) {
        return Totp.code(seed, Instant.now().getEpochSecond() / 30);
    }
}
