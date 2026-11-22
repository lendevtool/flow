package me.lendev.flow.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"flow.open-api.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:flowOpenRoleResourceCatalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
class OpenRoleResourceCatalogApiTest extends ApiTestBase {
    private static final String ADMIN_API = "/admin/open-api";
    private static final String MANAGED = "/open/v1/managed/";
    private static final String ROLES = "/role/catalog";
    private static final String RESOURCES = "/permission/catalog";
    private static final Set<String> ROLE_FIELDS = Set.of("id", "code", "name", "nameEn", "enable");
    private static final Set<String> RESOURCE_FIELDS = Set.of("id", "parentId", "code", "name", "nameEn", "type",
            "enable", "order");
    private static final String ENVIRONMENT = "catalog-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private JdbcClient fixture;

    @DynamicPropertySource
    static void isolateOpenApiRedisKeys(DynamicPropertyRegistry properties) {
        properties.add("flow.open-api.environment", () -> ENVIRONMENT);
    }

    @Test
    void offersCatalogSourcesAndKeepsEmployeeManagementRoutesPrivate() throws Exception {
        String admin = loginAsAdmin();
        var sources = data(getJson(ADMIN_API + "/endpoint-sources", admin, "importable", true));
        var paths = Set.of(ROLES, ROLES + "/{id}", RESOURCES, RESOURCES + "/{id}");
        long catalogs = 0;
        for (var source : sources) {
            String path = source.path("sourcePath").asString();
            if (paths.contains(path)) {
                catalogs++;
                assertEquals(path.startsWith(ROLES) ? "role" : "permission", source.path("moduleCode").asString());
                assertTrue(source.path("wholeResponse").asBoolean());
                assertEquals("NONE", source.path("dataScopeType").asString());
            }
            assertFalse(Set.of("/role", "/role/page", "/role/{id}", "/role/permissions", "/role/permissions/tree",
                    "/permission", "/permission/{id}", "/permission/tree", "/permission/menu/tree").contains(path));
        }
        assertEquals(4, catalogs);

        String qa = data(postJson("/auth/current-role/switch/ROLE_QA", admin, null)).path("accessToken").asString();
        for (String route : Set.of(ROLES, ROLES + "/1", RESOURCES, RESOURCES + "/1")) {
            code(getJson(route, qa), 11003);
        }
    }

    @Test
    void servesSanitizedCatalogsOnlyToApplicationsGrantedEachInterface() throws Exception {
        String admin = loginAsAdmin();
        String rolePage = MANAGED + "role-catalog";
        String roleDetail = MANAGED + "role-catalog-detail";
        String resourcePage = MANAGED + "resource-catalog";
        String resourceDetail = MANAGED + "resource-catalog-detail";
        long rolePageId = publish(admin, ROLES, rolePage, "role.catalog");
        long roleDetailId = publish(admin, ROLES + "/{id}", roleDetail, "role.catalog.detail");
        long resourcePageId = publish(admin, RESOURCES, resourcePage, "permission.catalog");
        long resourceDetailId = publish(admin, RESOURCES + "/{id}", resourceDetail, "permission.catalog.detail");
        String roleClient = applicationToken(admin, "角色目录应用", rolePageId, roleDetailId);
        String resourceClient = applicationToken(admin, "资源目录应用", resourcePageId, resourceDetailId);

        fixture.sql("UPDATE role SET nameEn = :name WHERE id = 2").param("name", "QA reviewer").update();
        var roles = body(getJson(rolePage, roleClient, "name", "QA reviewer", "enable", true, "pageSize", 1));
        assertEquals(1, roles.path("total").asLong());
        var role = roles.path("pageData").get(0);
        assertFields(role, ROLE_FIELDS);
        assertEquals("ROLE_QA", role.path("code").asString());
        assertEquals(role, body(getJson(roleDetail, roleClient, "id", 2)));
        assertEquals(0, body(getJson(rolePage, roleClient, "name", "QA reviewer", "enable", false)).path("total").asLong());
        assertEquals(0, body(getJson(rolePage, roleClient, "name", "QA reviewer's role")).path("total").asLong());
        assertEquals(0, body(getJson(rolePage, roleClient, "name", "QA reviewer", "pageNo", 2)).path("pageData").size());

        assertResourceCatalog(resourcePage, resourceDetail, resourceClient);

        getJson(rolePage, resourceClient).andExpect(status().isForbidden());
        getJson(roleDetail, resourceClient, "id", 2).andExpect(status().isForbidden());
        getJson(resourcePage, roleClient).andExpect(status().isForbidden());
        getJson(resourceDetail, roleClient, "id", 1).andExpect(status().isForbidden());
        getJson(ROLES, roleClient).andExpect(status().isUnauthorized());
        getJson(RESOURCES, resourceClient).andExpect(status().isUnauthorized());
        getJson(rolePage, roleClient, "pageSize", 101).andExpect(status().isBadRequest());
        getJson(resourcePage, resourceClient, "pageSize", 101).andExpect(status().isBadRequest());
        getJson(roleDetail, roleClient, "id", 0).andExpect(status().isBadRequest());
        getJson(resourceDetail, resourceClient, "id", 0).andExpect(status().isBadRequest());
        assertEquals("RESOURCE_NOT_FOUND", body(getJson(roleDetail, roleClient, "id", Long.MAX_VALUE), 404)
                .path("code").asString());
        assertEquals("RESOURCE_NOT_FOUND", body(getJson(resourceDetail, resourceClient, "id", Long.MAX_VALUE), 404)
                .path("code").asString());
    }

    private void assertResourceCatalog(String resourcePage, String resourceDetail, String resourceClient) throws Exception {
        var resources = body(getJson(resourcePage, resourceClient, "pageSize", 1));
        assertTrue(resources.path("total").asLong() > 1);
        assertEquals(1, resources.path("pageData").size());
        var resource = resources.path("pageData").get(0);
        assertFields(resource, RESOURCE_FIELDS);
        assertEquals("Resource_Mgt", resource.path("code").asString());
        assertEquals(2, resource.path("parentId").asLong());
        assertEquals(resource, body(getJson(resourceDetail, resourceClient, "id", 1)));
        assertEquals(2, body(getJson(resourcePage, resourceClient, "pageNo", 2, "pageSize", 1))
                .path("pageData").get(0).path("id").asLong());
    }

    private static void assertFields(JsonNode row, Set<String> fields) {
        assertEquals(fields, Set.copyOf(row.propertyNames()));
    }

    private long publish(String admin, String source, String path, String scope) throws Exception {
        String endpoints = ADMIN_API + "/endpoints";
        var imported = data(postJson(endpoints + "/import", admin, map("sourcePath", source, "displayName", scope,
                "endpointCode", scope, "pathTemplate", path)));
        long id = imported.path("id").asLong();
        update(admin, endpoints + "/" + id + "/status", map("status", "PUBLISHED", "version", 0));
        return id;
    }

    private String applicationToken(String admin, String name, long... endpoints) throws Exception {
        var app = data(postJson(ADMIN_API + "/apps", admin, map("name", name)));
        String appPath = ADMIN_API + "/apps/" + app.path("id").asLong();
        for (long id : endpoints) {
            update(admin, appPath + "/grants/" + id, map("status", "GRANTED", "version", null));
        }
        String secret = body(postJson(appPath + "/secrets", admin, null), 201).path("data").path("clientSecret").asString();
        String pair = app.path("clientId").asString() + ":" + secret;
        String basic = Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.US_ASCII));
        return body(mockMvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "client_credentials")))
                .path("access_token").asString();
    }

    private void update(String admin, String path, Object payload) throws Exception {
        data(mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(payload))));
    }
}
