package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import me.lendev.flow.mail.MailStore.SendReservation;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@TestPropertySource(properties = {
        "flow.mail.credential-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.datasource.url=jdbc:h2:mem:mail-admin-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
class MailAdminApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MailStore store;

    @Autowired
    MailAdminService service;

    @Test
    void credentialIsEncryptedAndExcludedFromManagementAndAuditPayloads() throws Exception {
        String token = loginAsAdmin();
        String secret = "example-secret-never-return";
        var request = account("AUDIT_MAIL", secret);
        var account = data(postJson("/mail/accounts", token, request));
        long id = account.path("id").asLong();
        var listed = data(getJson("/mail/accounts", token, "keyword", "audit_mail", "enable", true))
                .path("records").get(0);
        String encrypted = encrypted(id);
        String audit = latestAudit("system.mail.account.create");

        assertTrue(account.path("credentialConfigured").asBoolean());
        assertEquals("AUDIT_MAIL", listed.path("code").asString());
        assertFalse(encrypted.contains(secret));
        assertFalse(account.toString().contains("credentialCiphertext"));
        assertFalse(listed.toString().contains(secret));
        assertFalse(audit.contains(encrypted));
        assertFalse(audit.contains(secret));
    }

    @Test
    void editPreservesAnOmittedCredentialAndAuditsExplicitReplacementSafely() throws Exception {
        String token = loginAsAdmin();
        String first = "original-example-secret";
        var request = account("change_mail", first);
        var created = data(postJson("/mail/accounts", token, request));
        long id = created.path("id").asLong();
        String encrypted = encrypted(id);

        request.remove("credentials");
        request.put("name", "Renamed");
        var updated = data(putJson("/mail/accounts/" + id, token, request));
        assertEquals("CHANGE_MAIL", updated.path("code").asString());
        assertEquals(encrypted, encrypted(id));
        assertEquals(first, service.loadAccountConnection("change_mail").connection().credentials().clientSecret());

        String replacement = "replacement-example-secret";
        request.put("credentials", map("clientSecret", replacement));
        data(putJson("/mail/accounts/" + id, token, request));
        String nextEncrypted = encrypted(id);
        String audit = latestAudit("system.mail.account.update");

        assertNotEquals(encrypted, nextEncrypted);
        assertEquals(replacement, service.loadAccountConnection(" change_mail ").connection().credentials().clientSecret());
        assertTrue(audit.contains("credential"));
        for (String sensitiveValue : List.of(first, replacement, encrypted, nextEncrypted)) {
            assertFalse(audit.contains(sensitiveValue));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void logDetailsExposeTheSubmittedRevisionAndSafeMetadataThroughFilters() throws Exception {
        String token = loginAsAdmin();
        var account = data(postJson("/mail/accounts", token, account("LOG_SENDER", "fixture-secret")));
        long accountId = account.path("id").asLong();
        var request = map("name", "Notice", "code", "LOG_TEMPLATE", "accountId", accountId,
                "subjectTemplate", "Notice", "bodyTemplate", "First version", "sendMode", "INDIVIDUAL", "enable", true);
        var template = data(postJson("/mail/templates", token, request));
        long templateId = template.path("id").asLong();
        request.put("bodyTemplate", "Second version");
        var edited = data(putJson("/mail/templates/" + templateId, token, request));
        assertEquals(2, edited.path("revision").asInt());
        assertFalse(latestAudit("system.mail.template.update").contains("Second version"));

        var reservation = new SendReservation(accountId, templateId, "LOG_SENDER", "LOG_TEMPLATE", 1,
                "mail@example.com", Provider.GRAPH, SendMode.INDIVIDUAL, "USER:1", "test-request", "log-test", "c".repeat(64), 1);
        var stored = store.reserve(reservation, List.of(List.of(new Recipient("recipient@example.com", null))));
        long detailId = stored.details().getFirst().id();
        assertTrue(store.claim(detailId));
        store.finish(detailId, new Submission(Status.ACCEPTED, "0de70186-c370-4d04-a608-5cf88f606baa", null, null, List.of()));

        long logId = stored.log().id();
        var records = data(getJson("/mail/logs", token, "accountId", accountId, "templateId", templateId,
                "status", "ACCEPTED", "from", "2000-01-01T00:00:00Z", "to", "2100-01-01T00:00:00Z"));
        assertEquals(1, records.path("total").asInt());
        assertEquals(logId, records.path("records").get(0).path("id").asLong());
        var log = data(getJson("/mail/logs/" + logId, token));
        assertEquals(logId, log.path("id").asLong());
        assertEquals(1, log.path("templateRevision").asInt());
        assertFalse(log.has("requestDigest"));
        assertFalse(log.has("idempotencyKey"));
        assertEquals("recipient@example.com",
                log.path("details").get(0).path("recipients").get(0).path("address").asString());
        var options = data(getJson("/mail/logs/options", token));
        assertFalse(options.path("accounts").isEmpty());
        assertFalse(options.path("templates").isEmpty());
    }

    @Test
    void imapAndSmtpPasswordsCanBeUpdatedIndependently() throws Exception {
        String token = loginAsAdmin();
        var request = imapAccount(" imap-example ", true);
        var account = data(postJson("/mail/accounts", token, request));
        long id = account.path("id").asLong();
        assertEquals("IMAP-EXAMPLE", account.path("code").asString());

        request.put("credentials", map("imapPassword", "new-imap"));
        data(putJson("/mail/accounts/" + id, token, request));
        assertEquals(new Credentials("new-imap", "original-smtp", null),
                service.loadAccountConnection("imap-example").connection().credentials());

        request.put("credentials", map("smtpPassword", "new-smtp"));
        data(putJson("/mail/accounts/" + id, token, request));
        assertEquals(new Credentials("new-imap", "new-smtp", null),
                service.loadAccountConnection("imap-example").connection().credentials());

        request.remove("credentials");
        request.put("enable", false);
        data(putJson("/mail/accounts/" + id, token, request));
        var disabled = assertThrows(MailOperationException.class, () -> service.loadAccountConnection("IMAP-EXAMPLE"));
        assertEquals("MAIL_ACCOUNT_DISABLED", disabled.code());
        assertFalse(data(getJson("/mail/accounts/options", token)).get(0).path("enable").asBoolean());
    }

    @Test
    void configurationRejectsIncompleteSettingsAndCredentials() throws Exception {
        String token = loginAsAdmin();
        var incomplete = imapAccount("bad-settings", true);
        incomplete.put("config", map("imap", map("host", "https://bad.example.com/", "port", 993,
                "username", "reader", "tls", "SSL")));
        assertError(postJson("/mail/accounts", token, incomplete), 400, "MAIL_ENDPOINT_INVALID");

        var unconfigured = imapAccount("incomplete-credentials", true);
        unconfigured.put("credentials", map("imapPassword", "only-imap"));
        assertError(postJson("/mail/accounts", token, unconfigured), 400, "MAIL_CREDENTIAL_REQUIRED");
        unconfigured.put("credentials", map("imapPassword", "a".repeat(2049)));
        assertError(postJson("/mail/accounts", token, unconfigured), 400, "MAIL_CREDENTIAL_INVALID");

        var badGraph = account("bad-graph", "example-secret");
        badGraph.put("config", map("graph", map("tenantId", "/path", "clientId", "not-a-client-id")));
        assertError(postJson("/mail/accounts", token, badGraph), 400, "MAIL_GRAPH_SETTINGS_INVALID");
        badGraph.put("config", map("graph", graph("invalid-user-id")));
        assertError(postJson("/mail/accounts", token, badGraph), 400, "MAIL_GRAPH_MAILBOX_ID_INVALID");
    }

    @Test
    void configurationCodesAreUniqueAndProviderIsImmutable() throws Exception {
        String token = loginAsAdmin();
        var account = account("unique", "example-secret");
        long id = data(postJson("/mail/accounts", token, account)).path("id").asLong();
        assertError(postJson("/mail/accounts", token, account("UNIQUE", "example-secret")), 409, "MAIL_ACCOUNT_CODE_CONFLICT");
        assertError(putJson("/mail/accounts/" + id, token, imapAccount("unique", true)), 409,
                "MAIL_ACCOUNT_PROVIDER_IMMUTABLE");
        assertError(putJson("/mail/accounts/" + Long.MAX_VALUE, token, account), 404, "MAIL_ACCOUNT_NOT_FOUND");
        var missing = assertThrows(MailOperationException.class, () -> service.loadAccountConnection("missing"));
        assertEquals("MAIL_ACCOUNT_NOT_FOUND", missing.code());
        var invalidCode = assertThrows(MailOperationException.class, () -> service.loadAccountConnection("invalid/mail"));
        assertEquals("MAIL_CODE_INVALID", invalidCode.code());
    }

    @Test
    void templateCannotNewlyBindDisabledAccountAndMetadataEditsRetainRevision() throws Exception {
        String token = loginAsAdmin();
        var graphAccount = account("binding-account", "example-secret");
        graphAccount.put("config", map("graph", graph("ad2f203e-4029-427e-8b33-bda725794099")));
        long accountId = data(postJson("/mail/accounts", token, graphAccount)).path("id").asLong();
        var template = map("code", "binding-template", "name", "Original name", "enable", true,
                "accountId", accountId, "subjectTemplate", "Notice", "bodyTemplate", "First content");
        var saved = data(postJson("/mail/templates", token, template));
        long templateId = saved.path("id").asLong();
        assertEquals("INDIVIDUAL", saved.path("sendMode").asString());
        assertError(postJson("/mail/templates", token, template), 409, "MAIL_TEMPLATE_CODE_CONFLICT");

        graphAccount.put("enable", false);
        data(putJson("/mail/accounts/" + accountId, token, graphAccount));
        template.put("name", "Changed name");
        template.put("enable", false);
        var edited = data(putJson("/mail/templates/" + templateId, token, template));
        assertEquals(1, edited.path("revision").asInt());
        assertEquals("ad2f203e-4029-427e-8b33-bda725794099",
                data(getJson("/mail/accounts", token)).path("records").get(0)
                        .path("config").path("graph").path("mailboxId").asString());
        template.put("code", "another-template");
        assertError(postJson("/mail/templates", token, template), 400, "MAIL_TEMPLATE_ACCOUNT_DISABLED");

        var page = data(getJson("/mail/templates", token, "keyword", "binding", "enable", false));
        assertEquals(1, page.path("total").asInt());
        assertEquals("Changed name", page.path("records").get(0).path("name").asString());

        assertError(putJson("/mail/templates/" + Long.MAX_VALUE, token, template), 404, "MAIL_TEMPLATE_NOT_FOUND");
        var disabled = assertThrows(MailOperationException.class, () -> service.loadTemplate("binding-template"));
        assertEquals("MAIL_TEMPLATE_DISABLED", disabled.code());
        var missing = assertThrows(MailOperationException.class, () -> service.loadTemplate("missing"));
        assertEquals("MAIL_TEMPLATE_NOT_FOUND", missing.code());
    }

    @Test
    void templatePermissionAllowsOptionsWithoutAccountOrLogAccess() throws Exception {
        jdbc.update("""
                INSERT INTO role_permissions_permission (roleId, permissionId)
                SELECT 2, id FROM permission WHERE code='MailTemplate'
                """);
        String admin = loginAsAdmin();
        String limited = data(postJson("/auth/current-role/switch/ROLE_QA", admin, null)).path("accessToken").asString();
        data(getJson("/mail/accounts/options", limited));
        data(getJson("/mail/templates", limited));
        code(getJson("/mail/accounts", limited), 11003);
        code(getJson("/mail/logs/options", limited), 11003);
    }

    @Test
    void storedCredentialAndConfigErrorsUseSafeMessages() throws Exception {
        String token = loginAsAdmin();
        long id = data(postJson("/mail/accounts", token, account("damage", "example-secret"))).path("id").asLong();
        for (String invalid : List.of("unsupported", "v1.a", "v1." + Base64.getEncoder().encodeToString(new byte[4]),
                "v1." + Base64.getEncoder().encodeToString(new byte[36]))) {
            jdbc.update("UPDATE sys_mail_account SET credentialCiphertext=? WHERE id=?", invalid, id);
            var failure = assertThrows(MailOperationException.class, () -> service.loadAccountConnection("damage"));
            assertEquals("MAIL_CREDENTIAL_UNAVAILABLE", failure.code());
        }
        jdbc.update("UPDATE sys_mail_account SET configJson=? WHERE id=?", "bad config", id);
        var failure = assertThrows(MailOperationException.class, () -> service.loadAccountConnection("damage"));
        assertEquals("MAIL_CONFIG_UNAVAILABLE", failure.code());

        var invalidKey = new MailCredentialsCipher(objectMapper, "z!");
        var supplied = new Credentials(null, null, "example-secret");
        var keyFailure = assertThrows(MailOperationException.class, () -> invalidKey.seal(Provider.GRAPH, supplied));
        assertEquals("MAIL_KEY_UNAVAILABLE", keyFailure.code());
    }

    @Test
    void logTimeWindowMustIncreaseAndMissingLogIsReported() throws Exception {
        String token = loginAsAdmin();
        assertError(getJson("/mail/logs", token, "from", "2099-01-01T00:00:00Z", "to", "1999-01-01T00:00:00Z"),
                400, "MAIL_LOG_TIME_RANGE");
        assertError(getJson("/mail/logs/" + Long.MAX_VALUE, token), 404, "MAIL_LOG_NOT_FOUND");
    }

    private void assertError(ResultActions response, int status, String errorCode) throws Exception {
        response.andExpect(header().string("X-Mail-Error-Code", errorCode));
        assertEquals(status, body(response, status).path("code").asInt());
    }

    private Map<String, Object> imapAccount(String code, boolean enable) {
        return map("code", code, "name", "Self-hosted Exchange", "provider", "IMAP_SMTP", "enable", enable,
                "mailboxAddress", "mail@example.com",
                "config", map("imap", map("host", "imap.example.com", "port", 993, "username", "reader", "tls", "SSL"),
                        "smtp", map("host", "smtp.example.com", "port", 587, "username", "writer", "tls", "STARTTLS")),
                "credentials", map("imapPassword", "original-imap", "smtpPassword", "original-smtp"));
    }

    private Map<String, Object> account(String code, String secret) {
        return map("code", code, "name", "Audit mail", "provider", "GRAPH", "enable", true,
                "mailboxAddress", "mail@example.com",
                "config", map("graph", graph("upn@example.com")), "credentials", map("clientSecret", secret));
    }

    private Map<String, Object> graph(String mailboxId) {
        return map("tenantId", "89ef1f2e-2e01-4664-8d56-65c87d2d4703",
                "clientId", "73952fdc-376f-44f5-a07b-aaeeaa9e9591", "mailboxId", mailboxId);
    }

    private ResultActions putJson(String url, String token, Object request) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(request)));
    }

    private String encrypted(long id) {
        return jdbc.queryForObject("SELECT credentialCiphertext FROM sys_mail_account WHERE id=?", String.class, id);
    }

    private String latestAudit(String operation) {
        return jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1",
                String.class, operation);
    }
}
