package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"flow.open-api.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:flowOpenParamCatalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
class OpenParamCatalogApiTest extends ApiTestBase {
    private static final String ADMIN_API = "/admin/open-api";
    private static final String GROUPS = "/param/catalog/groups";
    private static final String PUBLIC = "/open/v1/managed/";

    @DynamicPropertySource
    static void openApiEnvironment(DynamicPropertyRegistry properties) {
        String environment = "param-" + UUID.randomUUID().toString().substring(0, 8);
        properties.add("flow.open-api.environment", () -> environment);
    }

    @Test
    void discoversOnlyParameterMetadataSources() throws Exception {
        String admin = loginAsAdmin();
        JsonNode sources = data(getJson(ADMIN_API + "/endpoint-sources", admin, "importable", true));
        var expected = Set.of(GROUPS, GROUPS + "/{groupId}", GROUPS + "/{groupId}/items",
                GROUPS + "/{groupId}/items/{id}");
        var actual = new HashSet<String>();
        for (JsonNode source : sources) {
            String path = source.path("sourcePath").asString();
            if (path.startsWith("/param/")) {
                actual.add(path);
                assertEquals("NONE", source.path("dataScopeType").asString());
                assertTrue(source.path("wholeResponse").asBoolean());
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void interfaceGrantsReturnParameterMetadataAndEnforceGroupMembership() throws Exception {
        String admin = loginAsAdmin();
        long groupId = data(postJson("/param/groups", admin, map("code", "catalog_sample", "name", "参数分类",
                "nameEn", "Catalog Sample", "sort", 3, "remark", "private note",
                "fieldConfig", map("fixedFields",
                        map("value1", map("label", "内部值", "type", "text", "defaultValue", "internal default"))))))
                .path("id").asLong();
        long otherGroup = data(postJson("/param/groups", admin, map("code", "catalog_other", "name", "其他分类")))
                .path("id").asLong();
        String rawItems = "/param/groups/" + groupId + "/items";
        long itemId = data(postJson(rawItems, admin, map("code", "catalog_entry", "name", "参数选项",
                "nameEn", "Bob's option", "enable", false, "sort", 2, "value1", "internal value",
                "remark", "private note"))).path("id").asLong();
        var app = data(postJson(ADMIN_API + "/apps", admin, map("name", "参数参考应用")));
        String appPath = ADMIN_API + "/apps/" + app.path("id").asLong();
        publishAndGrant(admin, appPath, GROUPS, "param-groups");
        publishAndGrant(admin, appPath, GROUPS + "/{groupId}", "param-group");
        publishAndGrant(admin, appPath, GROUPS + "/{groupId}/items", "param-items");
        publishAndGrant(admin, appPath, GROUPS + "/{groupId}/items/{id}", "param-item");
        String token = applicationToken(admin, app, appPath);

        assertGroupMetadata(admin, token, groupId);

        JsonNode items = body(getJson(PUBLIC + "param-items", token, "groupId", groupId, "keyword", "Bob's"));
        assertEquals(1, items.path("total").asInt());
        JsonNode item = items.path("pageData").get(0);
        assertEquals(itemId, item.path("id").asLong());
        assertEquals(groupId, item.path("groupId").asLong());
        assertEquals("catalog_entry", item.path("code").asString());
        assertEquals("参数选项", item.path("name").asString());
        assertEquals("Bob's option", item.path("nameEn").asString());
        assertFalse(item.path("enable").asBoolean(true));
        assertEquals(2, item.path("sort").asInt());
        assertEquals(7, item.size());
        assertEquals(item, body(getJson(PUBLIC + "param-item", token, "groupId", groupId, "id", itemId)));
        assertEquals(0, body(getJson(PUBLIC + "param-items", token, "groupId", otherGroup)).path("total").asInt());
        assertEquals("RESOURCE_NOT_FOUND", body(getJson(PUBLIC + "param-item", token, "groupId", otherGroup,
                "id", itemId), 404).path("code").asString());
        assertEquals("RESOURCE_NOT_FOUND", body(getJson(PUBLIC + "param-group", token, "groupId", Long.MAX_VALUE),
                404).path("code").asString());
        getJson(PUBLIC + "param-groups", token, "pageSize", 501).andExpect(status().isBadRequest());
        getJson(PUBLIC + "param-items", token, "groupId", groupId, "keyword", "x".repeat(101))
                .andExpect(status().isBadRequest());
        getJson(PUBLIC + "param-item", token, "groupId", groupId, "id", 0).andExpect(status().isBadRequest());
        getJson(GROUPS, token).andExpect(status().isUnauthorized());
        getJson(rawItems, token).andExpect(status().isUnauthorized());
        data(postJson("/auth/current-role/switch/ROLE_QA", admin, map()));
        code(getJson(GROUPS, admin), 11003);
    }

    private void assertGroupMetadata(String admin, String token, long groupId) throws Exception {
        JsonNode groups = body(getJson(PUBLIC + "param-groups", token, "keyword", "catalog_sample", "pageSize", 1));
        assertEquals(1, groups.path("total").asInt());
        JsonNode group = groups.path("pageData").get(0);
        assertEquals(groupId, group.path("id").asLong());
        assertEquals("catalog_sample", group.path("code").asString());
        assertEquals("参数分类", group.path("name").asString());
        assertEquals("Catalog Sample", group.path("nameEn").asString());
        assertTrue(group.path("enable").asBoolean());
        assertEquals(3, group.path("sort").asInt());
        assertEquals(6, group.size());
        assertEquals(group, body(getJson(PUBLIC + "param-group", token, "groupId", groupId)));
        assertEquals(group, data(getJson(GROUPS + "/" + groupId, admin)));
    }

    private void publishAndGrant(String admin, String appPath, String source, String slug) throws Exception {
        String endpoints = ADMIN_API + "/endpoints";
        var imported = data(postJson(endpoints + "/import", admin, map("sourcePath", source, "displayName", slug,
                "endpointCode", "catalog." + slug, "pathTemplate", PUBLIC + slug)));
        long endpointId = imported.path("id").asLong();
        putData(endpoints + "/" + endpointId + "/status", admin, map("status", "PUBLISHED", "version", 0));
        putData(appPath + "/grants/" + endpointId, admin, map("status", "GRANTED"));
    }

    private String applicationToken(String admin, JsonNode app, String appPath) throws Exception {
        String secret = body(postJson(appPath + "/secrets", admin, null), 201)
                .path("data").path("clientSecret").asString();
        String credential = app.path("clientId").asString() + ":" + secret;
        String basic = Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.US_ASCII));
        return body(mockMvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "client_credentials")))
                .path("access_token").asString();
    }

    private void putData(String path, String token, Map<String, Object> request) throws Exception {
        data(mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))));
    }
}
