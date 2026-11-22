package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

class AuthAuditApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode latest(String code) {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1", String.class, code));
    }

    @Test
    void loginAndLogoutFreezeVerifiedIdentityWithoutCredentials() throws Exception {
        String token = loginAsAdmin();
        JsonNode login = latest("system.auth.login");
        assertEquals("SUCCESS", login.path("result").asString());
        assertEquals("USER", login.path("actor").path("type").asString());
        assertEquals("admin", login.path("actor").path("account").asString());
        assertEquals("Admin", login.path("actor").path("name").asString());
        assertEquals("admin", login.path("claimedAccount").asString());
        assertFalse(login.toString().contains(token));
        assertFalse(login.toString().contains("\"password\""));
        assertFalse(login.toString().contains("captcha"));
        data(postJson("/auth/logout", token, map()));
        JsonNode logout = latest("system.auth.logout");
        assertEquals("SUCCESS", logout.path("result").asString());
        assertEquals("1", logout.path("actor").path("id").asString());
        assertEquals("admin", logout.path("actor").path("account").asString());
        body(getJson("/user/detail", token), 401);
    }

    @Test
    void badPasswordAndCaptchaRemainAnonymousWithClaimedAccount() throws Exception {
        Pair<String, ICaptcha> challenge = captchaService.create();
        code(postJson("/auth/login", map("username", "admin", "password", "never-save-wrong-password",
                "captchaKey", challenge.getKey(), "captcha", challenge.getValue().getCode())), 10002);
        JsonNode failed = latest("system.auth.login");
        assertEquals("FAILURE", failed.path("result").asString());
        assertEquals("ANONYMOUS", failed.path("actor").path("type").asString());
        assertTrue(failed.path("actor").path("account").isNull());
        assertEquals("admin", failed.path("claimedAccount").asString());
        assertEquals("10002", failed.path("businessCode").asString());
        assertFalse(failed.toString().contains("never-save-wrong-password"));
        assertFalse(failed.toString().contains(challenge.getKey()));
        code(postJson("/auth/login", map("username", "missing-account", "password", "never-save-input",
                "captchaKey", "missing-challenge", "captcha", "xxxx")), 10003);
        assertEquals("10003", latest("system.auth.login").path("businessCode").asString());
    }

    @Test
    void manualParameterCacheRefreshHasExternalIntentResult() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/param/cache/refresh", token, map()));
        JsonNode event = latest("system.param.cache.refresh");
        assertEquals("EXTERNAL_RESULT", event.path("phase").asString());
        assertEquals("SUCCESS", event.path("result").asString());
        assertEquals("admin", event.path("actor").path("account").asString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedInitialLoginIntentDoesNotConsumeChallengeOrCreateSession() throws Exception {
        Pair<String, ICaptcha> challenge = captchaService.create();
        long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM sys_audit_outbox", Long.class);
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_login_intent CHECK (id <= " + existing
                + " OR operationCode <> 'system.auth.login')");
        try {
            body(postJson("/auth/login", map("username", "admin", "password", ADMIN_PASSWORD,
                    "captchaKey", challenge.getKey(), "captcha", challenge.getValue().getCode())), 503);
            assertTrue(captchaService.verify(challenge.getKey(), challenge.getValue().getCode()));
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_login_intent");
        }
    }
}
