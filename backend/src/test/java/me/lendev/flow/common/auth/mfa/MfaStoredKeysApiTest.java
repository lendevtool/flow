package me.lendev.flow.common.auth.mfa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.Base64;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flow_mfa_stored_keys;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "security.mfa.key-storage-encryption-key=QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
})
class MfaStoredKeysApiTest extends ApiTestBase {

    private static final String POLICY = "/system-policy/auth-security";
    private static final String KEYS = POLICY + "/mfa-keys";
    private static final String GENERATE = KEYS + "/generate";
    private static final String ROTATION = POLICY + "/mfa-rotation";
    private static final String VERSION = "page-v2";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MfaSecrets secrets;
    @Autowired
    private MfaRotationWorker worker;

    @Test
    void generatesDistinctKeysWithoutStagingOrActivatingThem() throws Exception {
        String admin = loginAsAdmin();
        String key = data(postJson(GENERATE, admin, map())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))).path("key").asString();
        String secondKey = data(postJson(GENERATE, admin, map())).path("key").asString();
        assertEquals(32, Base64.getDecoder().decode(key).length);
        assertEquals(32, Base64.getDecoder().decode(secondKey).length);
        assertNotEquals(key, secondKey);
        assertTrue(data(getJson(KEYS, admin)).path("versions").isEmpty());
        assertFalse(secrets.configured());
        var before = data(getJson(ROTATION, admin));
        assertTrue(before.path("activeKeyId").isNull());
        assertTrue(before.path("nextRotationAt").isNull());
        assertFalse(before.path("overdue").asBoolean());
    }

    @Test
    void storesDraftEncryptedAndActivatesFirstKeyWithAudit() throws Exception {
        String admin = loginAsAdmin();
        String key = data(postJson(GENERATE, admin, map())).path("key").asString();
        var policy = data(getJson(POLICY, admin));
        ((ObjectNode) policy.path("config").path("mfa")).put("required", true);
        var update = map("revision", policy.path("revision").asLong(), "config", policy.path("config"));
        body(savePolicy(admin, update), 400);

        var draft = data(postJson(KEYS, admin, map("keyId", VERSION, "key", key)));
        var staged = draft.path("versions").get(0);
        assertEquals(VERSION, staged.path("id").asString());
        assertEquals("DRAFT", staged.path("status").asString());
        assertFalse(secrets.hasKey(VERSION));
        String encrypted = jdbc.queryForObject(
                "SELECT key_ciphertext FROM sys_mfa_key_material WHERE key_id=?", String.class, VERSION);
        assertNotEquals(key, encrypted);
        assertFalse(draft.toString().contains(key));
        assertFalse(draft.toString().contains(encrypted));
        assertFalse(data(getJson(POLICY, admin)).toString().contains(encrypted));
        assertTrue(data(getJson(ROTATION, admin)).path("activeKeyId").isNull());

        var published = data(postJson(KEYS + "/refresh", admin,
                map("revision", draft.path("revision").asLong(), "targetKeyId", VERSION, "activate", true)));
        assertEquals("PUBLISHED", published.path("versions").get(0).path("status").asString());
        assertEquals(VERSION, published.path("targetKeyId").asString());
        assertFalse(published.toString().contains(key));
        assertFalse(published.toString().contains(encrypted));
        assertTrue(secrets.hasKey(VERSION));
        assertEquals(VERSION, data(getJson(ROTATION, admin)).path("activeKeyId").asString());
        assertFalse(data(getJson(ROTATION, admin)).path("nextRotationAt").isNull());
        byte[] seed = secrets.newSecret();
        String ciphertext = secrets.protect(1, seed);
        assertTrue(ciphertext.startsWith(VERSION + ":"));
        assertArrayEquals(seed, secrets.open(1, ciphertext));
        assertTrue(data(savePolicy(admin, update)).path("config").path("mfa").path("required").asBoolean());

        assertKeyChangeAudits(key, encrypted);
    }

    private void assertKeyChangeAudits(String key, String encrypted) {
        var audits = jdbc.queryForList("""
                SELECT payload FROM sys_audit_outbox
                WHERE operationCode IN ('system.auth.mfa.key.stage', 'system.auth.mfa.key.refresh')
                ORDER BY id
                """, String.class);
        assertEquals(2, audits.size());
        for (String audit : audits) {
            assertTrue(audit.contains(VERSION));
            assertFalse(audit.contains(key));
            assertFalse(audit.contains(encrypted));
        }
        assertTrue(audits.getFirst().contains("待发布"));
        assertTrue(audits.getLast().contains("activeKeyId"));
    }

    @Test
    void publishingWithoutActivationMakesKeyAvailableForManualRotation() throws Exception {
        String admin = loginAsAdmin();
        var draft = data(postJson(KEYS, admin, map("keyId", VERSION, "key", newKey())));
        data(postJson(KEYS + "/refresh", admin,
                map("revision", draft.path("revision").asLong(), "targetKeyId", VERSION, "activate", false)));
        var before = data(getJson(ROTATION, admin));
        assertTrue(before.path("activeKeyId").isNull());
        assertFalse(before.path("overdue").asBoolean());
        worker.tick();
        assertFalse(secrets.configured());
        assertEquals(VERSION, before.path("targetKeyId").asString());
        assertTrue(before.path("canStart").asBoolean());
        assertEquals(VERSION, data(postJson(ROTATION + "/start", admin, map())).path("activeKeyId").asString());
    }

    @Test
    void rejectsStalePublicationAndRestrictsManagementToSuperAdmins() throws Exception {
        String admin = loginAsAdmin();
        var before = data(getJson(KEYS, admin));
        String key = newKey();
        data(postJson(KEYS, admin, map("keyId", VERSION, "key", key)));
        body(postJson(KEYS + "/refresh", admin,
                map("revision", before.path("revision").asLong(), "targetKeyId", VERSION, "activate", true)), 409);
        assertFalse(secrets.hasKey(VERSION));
        body(postJson(KEYS, admin, map("keyId", "copy", "key", key)), 400);
        body(postJson(KEYS, admin, map("keyId", VERSION, "key", newKey())), 400);

        var qa = data(postJson("/auth/current-role/switch/ROLE_QA", admin, map())).path("accessToken").asString();
        code(getJson(KEYS, qa), 11003);
        code(postJson(GENERATE, qa, map()), 11003);
        code(postJson(KEYS, qa, map("keyId", "qa", "key", newKey())), 11003);
        code(postJson(KEYS + "/refresh", qa, map("revision", 1, "targetKeyId", VERSION, "activate", true)), 11003);
    }

    private ResultActions savePolicy(String admin, Object update) throws Exception {
        return mockMvc.perform(put(POLICY).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(update)));
    }

    private static String newKey() {
        return Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    }
}
