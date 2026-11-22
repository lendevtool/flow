package me.lendev.flow.openapi.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class OpenApiAdminApiTest extends ApiTestBase {

    private static final String ADMIN_PATH = "/admin/open-api";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void filtersEndpointSourcesByImportEligibility() throws Exception {
        String token = loginAsAdmin();
        String path = ADMIN_PATH + "/endpoint-sources";
        var eligible = data(getJson(path, token, "importable", true));
        var blocked = data(getJson(path, token, "importable", false));
        assertFalse(eligible.isEmpty());
        assertFalse(blocked.isEmpty());
        for (var source : eligible) {
            assertTrue(source.path("importable").asBoolean());
            assertEquals("GET", source.path("httpMethod").asString());
            assertEquals("NONE", source.path("dataScopeType").asString());
        }
        for (var source : blocked) {
            assertFalse(source.path("importable").asBoolean());
        }
        assertEquals(eligible.size() + blocked.size(), data(getJson(path, token)).size());
        var invalidImport = body(postJson(ADMIN_PATH + "/endpoints/import", token, map())
                .andExpect(header().string("Cache-Control", "no-store")), 400);
        assertEquals("INVALID_ARGUMENT", invalidImport.path("data").path("errorCode").asString());
    }

    @Test
    void readsRegisteredEndpointsAndDownloadsOnlyPublishedPaths() throws Exception {
        String token = loginAsAdmin();
        var endpoints = data(getJson(ADMIN_PATH + "/endpoints", token))
                .path("pageData");
        assertEquals(14, endpoints.size());
        var endpoint = endpoints.get(0);
        long id = endpoint.path("id").asLong();
        long version = endpoint.path("version").asLong();
        String path = endpoint.path("pathTemplate").asString();
        var initial = body(getJson(ADMIN_PATH + "/docs/v1", token)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"open-api-v1.json\"")));
        assertTrue(initial.path("paths").has("/oauth2/token"));
        assertFalse(initial.path("paths").has(path));

        var published = data(update(ADMIN_PATH + "/endpoints/" + id + "/status", token,
                map("status", "PUBLISHED", "version", version)));
        assertEquals(version + 1, published.path("version").asLong());
        assertTrue(body(getJson(ADMIN_PATH + "/docs/v1", token)).path("paths").has(path));
        body(update(ADMIN_PATH + "/endpoints/" + id + "/status", token,
                map("status", "OFFLINE", "version", version)), 409);
    }

    @Test
    void createsUnrestrictedGrantAndRevokesLegacyGrantWithoutReadingItsScope() throws Exception {
        String token = loginAsAdmin();
        var application = data(postJson(ADMIN_PATH + "/apps", token,
                map("name", "Calendar integration", "description", "Initial use")));
        long appId = application.path("id").asLong();
        assertTrue(application.path("clientId").asString().startsWith("fa_"));
        var endpoint = data(getJson(ADMIN_PATH + "/endpoints", token)).path("pageData").get(0);
        assertEquals("NONE", endpoint.path("dataScopeType").asString());
        long endpointId = endpoint.path("id").asLong();
        data(update(ADMIN_PATH + "/endpoints/" + endpointId + "/status", token,
                map("status", "PUBLISHED", "version", endpoint.path("version").asLong())));
        assertTrue(data(getJson(ADMIN_PATH + "/scope-options", token, "type", "MARKET")).isEmpty());
        assertTrue(data(getJson(ADMIN_PATH + "/scope-options", token, "type", "INSTITUTION")).isEmpty());
        var grantPath = ADMIN_PATH + "/apps/" + appId + "/grants/" + endpointId;
        var first = data(update(grantPath, token, map("status", "GRANTED", "version", null)));
        assertEquals(0, first.path("version").asLong());
        assertEquals("NONE", first.path("dataScopeType").asString());
        assertEquals("NOT_APPLICABLE", first.path("dataScopeMode").asString());
        assertTrue(first.path("dataScope").isNull());

        jdbc.update("""
                UPDATE sys_open_api_app_grant SET data_scope_mode = 'RESTRICTED', data_scope_json = '[]'
                WHERE app_id = ? AND endpoint_id = ?
                """, appId, endpointId);
        assertEquals(first, data(getJson(ADMIN_PATH + "/apps/" + appId + "/grants", token)).get(0));

        var revoked = data(update(grantPath, token, map("status", "REVOKED", "version", 0)));
        assertEquals("REVOKED", revoked.path("status").asString());
        assertEquals("NOT_APPLICABLE", revoked.path("dataScopeMode").asString());
        assertTrue(revoked.path("dataScope").isNull());
        body(update(grantPath, token, map("status", "REVOKED", "version", 0)), 409);

        data(update(grantPath, token, map("status", "GRANTED", "version", revoked.path("version").asLong())));
        assertEquals("NOT_APPLICABLE", jdbc.queryForObject("""
                SELECT data_scope_mode FROM sys_open_api_app_grant WHERE app_id = ? AND endpoint_id = ?
                """, String.class, appId, endpointId));
        assertNull(jdbc.queryForObject("""
                SELECT data_scope_json FROM sys_open_api_app_grant WHERE app_id = ? AND endpoint_id = ?
                """, String.class, appId, endpointId));

        var updated = data(patchJson(ADMIN_PATH + "/apps/" + appId, token,
                map("description", null, "version", application.path("version").asLong())));
        assertTrue(updated.path("description").isNull() || updated.path("description").isMissingNode());
        assertEquals(application.path("name"), updated.path("name"));
    }

    @Test
    void searchesApplicationsAndShowsImpactBeforeOfflining() throws Exception {
        String token = loginAsAdmin();
        String applications = ADMIN_PATH + "/apps";
        var owners = data(getJson(ADMIN_PATH + "/owner-options", token));
        int ownerId = owners.path("pageData").get(0).path("id").asInt();
        var app = data(postJson(applications, token,
                map("name", "Daily_% report", "ownerUserId", ownerId, "rateLimitCount", 3, "rateLimitWindowSeconds", 30)));
        long appId = app.path("id").asLong();
        String appPath = applications + "/" + appId;
        assertEquals(ownerId, data(getJson(appPath, token)).path("ownerUserId").asInt());
        assertEquals(1, data(getJson(applications, token, "keyword", "_%", "enabled", true)).path("total").asInt());
        assertEquals(1, data(getJson(applications, token, "keyword", app.path("clientId").asString())).path("total").asInt());
        body(postJson(applications, token, map("name", "No owner", "ownerUserId", Integer.MAX_VALUE)), 400);
        body(getJson(applications + "/99999999", token), 404);

        var edited = data(patchJson(appPath, token, map("name", " Renamed ", "ownerUserId", null,
                "rateLimitCount", 5, "rateLimitWindowSeconds", 60, "version", app.path("version").asLong())));
        assertEquals("Renamed", edited.path("name").asString());
        assertTrue(edited.path("ownerUserId").isNull() || edited.path("ownerUserId").isMissingNode());
        assertEquals(5, edited.path("rateLimitCount").asInt());
        long version = edited.path("version").asLong();
        assertEquals(version, data(patchJson(appPath, token, map("version", version))).path("version").asLong());
        assertEquals(version, data(update(appPath + "/status", token, map("enabled", true, "version", version)))
                .path("version").asLong());
        body(patchJson(appPath, token, map("name", "  ", "version", version)), 400);

        var endpoint = data(getJson(ADMIN_PATH + "/endpoints", token)).path("pageData").get(0);
        long endpointId = endpoint.path("id").asLong();
        String endpointPath = ADMIN_PATH + "/endpoints/" + endpointId;
        assertEquals(endpoint.path("endpointCode"), data(getJson(endpointPath, token)).path("endpointCode"));
        body(getJson(ADMIN_PATH + "/endpoints/99999999", token), 404);
        var status = map("status", "REGISTERED", "version", endpoint.path("version").asLong());
        body(update(endpointPath + "/status", token, status), 400);
        status.put("status", "PUBLISHED");
        var published = data(update(endpointPath + "/status", token, status));
        status.put("version", published.path("version").asLong());
        assertEquals(published.path("version"), data(update(endpointPath + "/status", token, status)).path("version"));
        data(update(appPath + "/grants/" + endpointId, token, map("status", "GRANTED", "dataScopeMode", "ALL")));
        var impact = data(getJson(endpointPath + "/apps", token));
        assertEquals(1, impact.path("total").asInt());
        assertEquals(appId, impact.path("pageData").get(0).path("id").asLong());
        assertEquals(1, data(getJson(endpointPath, token)).path("authorizedAppCount").asInt());
        status.put("status", "OFFLINE");
        assertEquals("OFFLINE", data(update(endpointPath + "/status", token, status)).path("status").asString());
    }

    @Test
    void validatesGrantInputAndKeepsIdempotentUpdatesAtTheSameVersion() throws Exception {
        String token = loginAsAdmin();
        long appId = data(postJson(ADMIN_PATH + "/apps", token, map("name", "Grant validation"))).path("id").asLong();
        var endpoint = data(getJson(ADMIN_PATH + "/endpoints", token)).path("pageData").get(0);
        long endpointId = endpoint.path("id").asLong();
        String grants = ADMIN_PATH + "/apps/" + appId + "/grants";
        String target = grants + "/" + endpointId;
        body(update(target, token, map("status", "GRANTED", "dataScopeMode", "ALL")), 400);
        body(update(target, token, map("status", "REVOKED", "version", 0)), 404);
        data(update(ADMIN_PATH + "/endpoints/" + endpointId + "/status", token,
                map("status", "PUBLISHED", "version", endpoint.path("version").asLong())));

        body(update(target, token, map("status", "GRANTED", "dataScopeMode", "ALL", "rateLimitCount", 2)), 400);
        body(update(target, token, map("status", "GRANTED", "dataScopeMode", "ALL", "dataScope", map())), 400);
        body(update(target, token, map("status", "GRANTED", "dataScopeMode", "RESTRICTED")), 400);
        body(update(target, token, map("status", "GRANTED", "dataScopeMode", "RESTRICTED",
                "dataScope", map("marketItemIds", List.of(Integer.MAX_VALUE)))), 400);

        var request = map("status", "GRANTED", "dataScopeMode", "ALL", "dataScope", null);
        var saved = data(update(target, token, request));
        assertEquals("NOT_APPLICABLE", saved.path("dataScopeMode").asString());
        long version = saved.path("version").asLong();
        assertEquals(saved, data(update(target, token, map("status", "GRANTED", "version", version,
                "dataScopeMode", "NOT_APPLICABLE"))));
        assertEquals(saved, data(getJson(grants, token)).get(0));
        var revoked = data(update(target, token, map("status", "REVOKED", "version", version)));
        assertEquals(revoked, data(update(target, token, map("status", "REVOKED", "version", revoked.path("version").asLong()))));
    }

    @Test
    void rotatesSecretsAndKeepsCredentialsOutOfMetadataAndAudit() throws Exception {
        String token = loginAsAdmin();
        var application = data(postJson(ADMIN_PATH + "/apps", token, map("name", "Rotation integration")));
        String path = ADMIN_PATH + "/apps/" + application.path("id").asLong() + "/secrets";

        var generated = body(postJson(path, token, map())
                .andExpect(header().string("Cache-Control", "no-store")), 201).path("data");
        assertEquals(1, generated.path("versionNo").asInt());
        String secret = generated.path("clientSecret").asString();
        assertTrue(secret.startsWith("fsk_"));
        body(postJson(path, token, map()), 409);
        var activated = data(postJson(path + "/1/activate", token, map()));
        assertEquals("ACTIVE", activated.path("activated").path("status").asString());

        body(postJson(path, token, map()), 201);
        var secondActivation = data(postJson(path + "/2/activate", token, map()));
        assertEquals("RETIRING", secondActivation.path("replaced").path("status").asString());
        var repeated = data(postJson(path + "/1/activate", token, map()));
        assertEquals(secondActivation.path("replaced"), repeated.path("activated"));
        var retired = data(postJson(path + "/1/revoke", token, map("mode", "RETIRE")));
        assertEquals("REVOKED", retired.path("status").asString());
        assertTrue(retired.path("tokensRevokedAt").isNull() || retired.path("tokensRevokedAt").isMissingNode());
        var compromised = data(postJson(path + "/1/revoke", token, map("mode", "COMPROMISED")));
        assertTrue(compromised.path("tokensRevokedAt").isString());

        String metadata = data(getJson(path, token)).toString();
        assertFalse(metadata.contains("secretHash"));
        assertFalse(metadata.contains("clientSecret"));
        var outboxPayloads = jdbc.queryForList("""
                SELECT payload FROM sys_audit_outbox
                WHERE operationCode LIKE 'system.open-api.secret.%'
                """, String.class);
        assertFalse(outboxPayloads.isEmpty());
        assertFalse(outboxPayloads.toString().contains(secret));
        assertFalse(outboxPayloads.toString().contains("secretHash"));
    }

    @Test
    void replacesExpiredCandidateAndExplicitlyAbandonsUndeliveredSecret() throws Exception {
        String token = loginAsAdmin();
        long appId = data(postJson(ADMIN_PATH + "/apps", token, map("name", "Recover rotation"))).path("id").asLong();
        String secrets = ADMIN_PATH + "/apps/" + appId + "/secrets";
        body(postJson(secrets + "/1/activate", token, map()), 404);
        body(postJson(secrets, token, map()), 201);
        body(postJson(secrets + "/1/revoke", token, map("mode", "RETIRE")), 409);
        jdbc.update("UPDATE sys_open_api_app_secret SET expires_at = ? WHERE app_id = ? AND version_no = 1",
                LocalDateTime.of(2000, 1, 1, 0, 0), appId);
        body(postJson(secrets + "/1/activate", token, map()), 409);
        assertEquals(2, body(postJson(secrets, token, map()), 201).path("data").path("versionNo").asInt());
        var abandoned = data(postJson(secrets + "/2/revoke", token, map("mode", "ABANDON")));
        assertEquals("REVOKED", abandoned.path("status").asString());
        assertTrue(abandoned.path("tokensRevokedAt").isString());
        body(postJson(secrets + "/2/activate", token, map()), 409);
        body(postJson(secrets + "/2/revoke", token, map("mode", "ABANDON")), 409);
        var list = data(getJson(secrets, token));
        assertEquals("REVOKED", list.get(1).path("status").asString());
        assertTrue(list.get(1).path("tokensRevokedAt").isString());
    }

    @Test
    void filtersCallsAndRequiresViewInAdditionToCallLogPermission() throws Exception {
        String admin = loginAsAdmin();
        long app = data(postJson(ADMIN_PATH + "/apps", admin, map("name", "Call log integration"))).path("id").asLong();
        String calls = ADMIN_PATH + "/apps/" + app + "/calls";
        LocalDateTime time = LocalDateTime.of(2027, 1, 15, 12, 0);
        insertCall(app, "denied-call", time, 429);
        insertCall(app, "failed-call", time.plusSeconds(1), 503);
        insertCall(app, "success-call", time.plusSeconds(2), 200);
        insertCall(app + 1, "different-app", time.plusSeconds(2), 401);

        var results = data(getJson(calls, admin, "from", "2027-01-15T12:00:00Z",
                "to", "2027-01-15T12:00:01Z", "result", "DENIED"));
        assertEquals(1, results.path("total").asInt());
        assertEquals("denied-call", results.path("pageData").get(0).path("requestId").asString());
        fromFilter(calls, admin, "FAILED", "failed-call");
        fromFilter(calls, admin, "SUCCESS", "success-call");
        body(getJson(calls, admin, "from", "0999-01-01T00:00:00Z"), 400);
        body(getJson(calls, admin, "from", "2027-01-01T00:00:00Z", "to", "2027-02-02T00:00:00Z"), 400);

        jdbc.update("INSERT INTO role(code,name,enable) VALUES ('OPEN_API_LOG_TEST','调用记录查看者',1)");
        long role = jdbc.queryForObject("SELECT id FROM role WHERE code='OPEN_API_LOG_TEST'", Long.class);
        jdbc.update("""
                INSERT INTO `user` (username,password,identityKey,enable)
                SELECT 'open_api_log_viewer',password,RANDOM_UUID(),1 FROM `user` WHERE id=1
                """);
        jdbc.update("""
                INSERT INTO user_roles_role(userId,roleId)
                SELECT id,? FROM `user` WHERE username='open_api_log_viewer'
                """, role);
        jdbc.update("""
                INSERT INTO role_permissions_permission(roleId,permissionId)
                SELECT ?,id FROM permission WHERE code='OpenApiCallLogView'
                """, role);
        String viewer = login("open_api_log_viewer", ADMIN_PASSWORD);
        body(getJson(calls, viewer), 403);
        body(getJson(ADMIN_PATH + "/endpoints", viewer), 403);
        body(getJson(ADMIN_PATH + "/endpoint-sources", viewer), 403);
        var docsDenied = body(getJson(ADMIN_PATH + "/docs/v1", viewer)
                .andExpect(header().string("Cache-Control", "no-store")), 403);
        assertEquals("ACCESS_DENIED", docsDenied.path("data").path("errorCode").asString());
    }

    private void fromFilter(String calls, String admin, String result, String requestId) throws Exception {
        var found = data(getJson(calls, admin, "from", "2027-01-15T12:00:00Z", "result", result));
        assertEquals(1, found.path("total").asInt());
        assertEquals(requestId, found.path("pageData").get(0).path("requestId").asString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentGenerationCommitsOnlyOneCandidate() throws Exception {
        String token = loginAsAdmin();
        long appId = data(postJson(ADMIN_PATH + "/apps", token, map("name", "Concurrent secret generation")))
                .path("id").asLong();
        String path = ADMIN_PATH + "/apps/" + appId + "/secrets";
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return postJson(path, token, map()).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return postJson(path, token, map()).andReturn().getResponse().getStatus();
            });
            start.countDown();
            var statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
            var candidates = data(getJson(path, token));
            assertEquals(1, candidates.size());
            assertEquals("CANDIDATE", candidates.get(0).path("status").asString());
        } finally {
            jdbc.update("DELETE FROM sys_open_api_app_secret WHERE app_id = ?", appId);
            jdbc.update("DELETE FROM sys_open_api_app WHERE id = ?", appId);
        }
    }

    private void insertCall(long appId, String requestId, LocalDateTime occurredAt, int status) {
        jdbc.update("""
                INSERT INTO sys_open_api_call_log (app_id, request_id, occurred_at, duration_ms, http_status)
                VALUES (?, ?, ?, 5, ?)
                """, appId, requestId, occurredAt, status);
    }

    private org.springframework.test.web.servlet.ResultActions update(String path, String token, Object request)
            throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(request)));
    }
}
