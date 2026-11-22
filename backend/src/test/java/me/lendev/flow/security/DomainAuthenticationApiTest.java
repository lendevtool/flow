package me.lendev.flow.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

import me.lendev.flow.common.auth.domain.DomainAuthenticator;
import me.lendev.flow.common.auth.domain.DomainUnavailableException;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:flow_domain;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class DomainAuthenticationApiTest extends ApiTestBase {

    private static final String POLICY = "/system-policy/auth-security";
    private static final String DOMAIN_USER = "directory.user@company.example";
    private static final String DOMAIN_PASSWORD = "directory-test-credential";
    @MockitoBean
    private DomainAuthenticator directory;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void managementCreatesEditsAndClearsBothFieldsWithAudit() throws Exception {
        String admin = loginAsAdmin();
        var created = create(admin);
        assertTrue(created.path("temporaryPassword").isString());
        var row = userRow(admin);
        long id = row.path("id").asLong();
        assertEquals(DOMAIN_USER, row.path("domainAccount").asString());
        assertEquals("+8613800001234", row.path("mobile").asString());
        body(postJson("/user", admin, map("username", "domain_duplicate", "domainAccount", DOMAIN_USER.toUpperCase())), 400);

        data(patchJson("/user/" + id, admin, map("domainAccount", "new.user@company.example", "mobile", "13800005678")));
        row = userRow(admin);
        assertEquals("new.user@company.example", row.path("domainAccount").asString());
        assertEquals("13800005678", row.path("mobile").asString());
        var event = latestAudit("system.user.update");
        assertTrue(event.toString().contains("domainAccount"));
        assertTrue(event.toString().contains("mobile"));
        assertFalse(event.toString().contains("13800005678"));

        data(patchJson("/user/" + id, admin, map("nameEn", "Directory User")));
        assertEquals("13800005678", userRow(admin).path("mobile").asString());
        data(patchJson("/user/" + id, admin, map("domainAccount", null, "mobile", "")));
        assertTrue(userRow(admin).path("domainAccount").isNull());
        assertTrue(userRow(admin).path("mobile").isNull());
        body(patchJson("/user/" + id, admin, map("mobile", "invalid")), 400);
        body(patchJson("/user/" + id, admin, map("domainAccount", "missing-domain")), 400);
    }

    @Test
    void domainUsesOnlyDirectoryCredentialsAndSharesFlowSessionAndAuditRules() throws Exception {
        String admin = loginAsAdmin();
        create(admin);
        code(domainLogin(DOMAIN_USER, DOMAIN_PASSWORD), 10002);
        verify(directory, never()).authenticate(anyString(), anyString());
        when(directory.configured()).thenReturn(true);
        when(directory.authenticate(DOMAIN_USER, DOMAIN_PASSWORD)).thenReturn(true);
        data(updatePolicy(admin, true, true));
        var options = data(getJson("/auth/login/options", null));
        assertTrue(options.path("localEnabled").asBoolean());
        assertTrue(options.path("domainEnabled").asBoolean());
        assertTrue(latestAudit("system.policy.update").toString().contains("login.domainEnabled"));

        code(domainLogin(DOMAIN_USER, "wrong"), 10002);
        var login = data(domainLogin(DOMAIN_USER.toUpperCase(), DOMAIN_PASSWORD));
        assertFalse(login.path("passwordChangeRequired").asBoolean());
        assertFalse(latestAudit("system.auth.login").toString().contains(DOMAIN_PASSWORD));
        String token = login.path("accessToken").asString();
        var detail = data(getJson("/user/detail", token));
        assertEquals("directory_user", detail.path("username").asString());
        assertEquals("DOMAIN", detail.path("loginMethod").asString());
        assertEquals("ROLE_QA", detail.path("currentRole").path("code").asString());
        code(postJson("/auth/password", token, map("oldPassword", DOMAIN_PASSWORD, "newPassword", "Replacement123!")), 11006);
        code(postJson("/auth/login", map("username", "directory_user", "password", DOMAIN_PASSWORD)), 10002);
        code(domainLogin("unmapped@company.example", DOMAIN_PASSWORD), 10002);
        verify(directory, never()).authenticate(eq("unmapped@company.example"), anyString());
        when(directory.authenticate(DOMAIN_USER, DOMAIN_PASSWORD)).thenThrow(new DomainUnavailableException());
        body(domainLogin(DOMAIN_USER, DOMAIN_PASSWORD), 503);

        long id = userRow(admin).path("id").asLong();
        data(patchJson("/user/" + id, admin, map("domainAccount", "changed.user@company.example")));
        body(getJson("/user/detail", token), 401);
    }

    @Test
    void domainOnlyModeRequiresConfiguredDirectoryAndMappedAdmin() throws Exception {
        String admin = loginAsAdmin();
        body(updatePolicy(admin, false, false), 400);
        body(updatePolicy(admin, true, true), 400);
        when(directory.configured()).thenReturn(true);
        body(updatePolicy(admin, false, true), 400);
        data(patchJson("/user/1", admin, map("domainAccount", "admin@company.example")));
        admin = loginAsAdmin();
        data(updatePolicy(admin, false, true));
        assertFalse(data(getJson("/auth/login/options", null)).path("localEnabled").asBoolean());
        code(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD)), 10002);
        when(directory.authenticate("admin@company.example", DOMAIN_PASSWORD)).thenReturn(true);
        var token = data(domainLogin("admin@company.example", DOMAIN_PASSWORD)).path("accessToken").asString();
        assertEquals("SUPER_ADMIN", data(getJson("/user/detail", token)).path("currentRole").path("code").asString());
        var current = data(getJson(POLICY, token));
        var reset = data(postJson(POLICY + "/reset", token, map("revision", current.path("revision").asLong())));
        assertTrue(reset.path("config").path("login").path("localEnabled").asBoolean());
        assertFalse(reset.path("config").path("login").path("domainEnabled").asBoolean());
    }

    private JsonNode create(String admin) throws Exception {
        return data(postJson("/user", admin, map("username", "directory_user",
                "domainAccount", "Directory.User@Company.Example", "mobile", "+8613800001234", "roleIds", List.of(2))));
    }

    private JsonNode userRow(String token) throws Exception {
        return data(getJson("/user", token, "username", "directory_user")).path("pageData").get(0);
    }

    private JsonNode latestAudit(String operation) {
        return objectMapper.readTree(jdbc.queryForObject("""
                SELECT payload FROM sys_audit_outbox WHERE operationCode = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, operation));
    }

    private ResultActions updatePolicy(String token, boolean local, boolean domain) throws Exception {
        var current = data(getJson(POLICY, token));
        var config = (ObjectNode) current.path("config");
        ((ObjectNode) config.path("login")).put("localEnabled", local).put("domainEnabled", domain);
        ((ObjectNode) config.path("captcha")).put("required", false);
        return mockMvc.perform(put(POLICY).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("config", config, "revision", current.path("revision").asLong()))));
    }

    private ResultActions domainLogin(String account, String password) throws Exception {
        var captcha = captchaService.create();
        return postJson("/auth/login", Map.of("username", account, "password", password, "loginMethod", "DOMAIN",
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode()));
    }
}
