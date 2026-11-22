package me.lendev.flow.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"flow.open-api.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:flowOpenFieldApi;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
class OpenFieldApiTest extends ApiTestBase {
    private static final String ROOT = "/open/v1/field-definition";
    private static final String ADMIN_API = "/admin/open-api";
    private static final String EMPLOYEE = "/field-definition";
    private static final String MANAGED = "/open/v1/managed/field-public-comparison";
    private static final String ENVIRONMENT = "open-field-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void isolateApplicationTokens(DynamicPropertyRegistry properties) {
        properties.add("flow.open-api.environment", () -> ENVIRONMENT);
    }

    @Test
    void servesFiveDedicatedContractsAlongsideTheManagedEmployeeContract() throws Exception {
        String admin = loginAsAdmin();
        long id = createField(admin, "open_field_a");
        createField(admin, "open_field_b");
        var endpoints = publishDedicated(admin);
        String dedicated = applicationToken(admin, endpoints);
        long managedId = importManagedDetail(admin);
        String managed = applicationToken(admin, List.of(managedId));

        var employee = data(getJson(EMPLOYEE + "/" + id, admin));
        var detail = body(getJson(ROOT + "/" + id, dedicated)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")));
        assertEquals(Set.of("id", "config"), Set.copyOf(detail.propertyNames()));
        assertEquals(id, detail.path("id").asLong());
        assertEquals(employee.path("config"), detail.path("config"));
        assertTrue(employee.has("createdBy"));
        assertTrue(employee.has("updatedAt"));
        assertEquals(employee, body(getJson(MANAGED, managed, "id", id)));

        assertDedicatedPagination(dedicated);
        assertEquals(data(getJson(EMPLOYEE + "/metadata", admin)), body(getJson(ROOT + "/metadata", dedicated)));
        var dictionaries = data(getJson(EMPLOYEE + "/dictionaries", admin));
        assertEquals(dictionaries, body(getJson(ROOT + "/dictionaries", dedicated)));
        long dictionary = dictionaries.get(0).path("id").asLong();
        assertEquals(data(getJson(EMPLOYEE + "/dictionaries/" + dictionary + "/options", admin)),
                body(getJson(ROOT + "/dictionaries/" + dictionary + "/options", dedicated)));
        assertEquals(data(getJson(EMPLOYEE + "/dictionaries", admin, "keyword", "FIELD_TEXT_FORMAT", "selectedId", dictionary)),
                body(getJson(ROOT + "/dictionaries", dedicated, "keyword", "FIELD_TEXT_FORMAT", "selectedId", dictionary)));

        getJson(ROOT + "/" + id, managed).andExpect(status().isForbidden());
        getJson(MANAGED, dedicated, "id", id).andExpect(status().isForbidden());
        getJson(EMPLOYEE + "/" + id, dedicated).andExpect(status().isUnauthorized());
        getJson(ROOT + "/metadata", admin).andExpect(status().isUnauthorized());
        getJson(ROOT + "/metadata", null).andExpect(status().isUnauthorized());
    }

    @Test
    void validatesQueriesAndReturnsStableNotFoundErrors() throws Exception {
        String admin = loginAsAdmin();
        String token = applicationToken(admin, publishDedicated(admin));
        for (String path : List.of(ROOT + "/0", ROOT + "/dictionaries/0/options")) {
            assertEquals("INVALID_ARGUMENT", body(getJson(path, token), 400).path("code").asString());
        }
        for (String path : List.of(ROOT + "/9223372036854775807", ROOT + "/dictionaries/9223372036854775807/options")) {
            var error = body(getJson(path, token), 404);
            assertEquals("RESOURCE_NOT_FOUND", error.path("code").asString());
            assertTrue(error.hasNonNull("requestId"));
            assertFalse(error.toString().contains("error.field"));
        }
        for (var query : List.of(map("pageNo", 0), map("pageSize", 0), map("pageSize", 101),
                map("dataType", "UNKNOWN"), map("keyword", "a".repeat(101)))) {
            var entry = query.entrySet().iterator().next();
            assertEquals("INVALID_ARGUMENT", body(getJson(ROOT, token, entry.getKey(), entry.getValue()), 400)
                    .path("code").asString());
        }
        getJson(ROOT + "/dictionaries", token, "selectedId", 0).andExpect(status().isBadRequest());
        getJson(ROOT + "/dictionaries", token, "keyword", "a".repeat(101)).andExpect(status().isBadRequest());
        postJson(ROOT, token, map()).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void documentsBothModesAndRevokesDedicatedGrantsIndependently() throws Exception {
        String admin = loginAsAdmin();
        var endpoints = publishDedicated(admin);
        importManagedDetail(admin);
        var app = data(postJson(ADMIN_API + "/apps", admin, map("name", "字段元数据应用")));
        var metadata = findEndpoint(admin, "field-definition.public.metadata.read");
        String grant = ADMIN_API + "/apps/" + app.path("id").asLong() + "/grants/" + metadata.path("id").asLong();
        update(admin, grant, map("status", "GRANTED", "version", null));
        String token = token(admin, app);
        getJson(ROOT + "/metadata", token).andExpect(status().isOk());
        getJson(ROOT, token).andExpect(status().isForbidden());
        var document = body(getJson(ADMIN_API + "/docs/v1", admin));
        var paths = document.path("paths");
        assertTrue(paths.has(MANAGED));
        var expectedPaths = List.of(ROOT, ROOT + "/{id}", ROOT + "/metadata", ROOT + "/dictionaries",
                ROOT + "/dictionaries/{id}/options");
        for (String path : expectedPaths) {
            var operation = paths.path(path).path("get");
            assertTrue(operation.path("x-required-scope").asString().startsWith("field-definition.public."));
            assertTrue(operation.path("responses").has("400"));
        }
        var queryParameters = paths.path(ROOT).path("get").path("parameters");
        assertEquals(Set.of("keyword", "dataType", "enable", "pageNo", "pageSize"),
                StreamSupport.stream(queryParameters.spliterator(), false)
                        .map(parameter -> parameter.path("name").asString()).collect(java.util.stream.Collectors.toSet()));
        var id = paths.path(ROOT + "/{id}").path("get").path("parameters").get(0);
        assertEquals("path", id.path("in").asString());
        var definition = document.path("components").path("schemas").path("OpenFieldDefinition");
        assertEquals(Set.of("id", "config"), Set.copyOf(definition.path("properties").propertyNames()));
        var schemas = document.path("components").path("schemas");
        var defaultValue = schemas.path("OpenFieldConfig").path("properties").path("defaultValue");
        assertEquals("#/components/schemas/JsonNode", defaultValue.path("$ref").asString());
        var jsonValue = schemas.path("JsonNode");
        assertTrue(jsonValue.hasNonNull("description"));
        assertFalse(jsonValue.has("type"));
        assertFalse(jsonValue.has("properties"));
        assertFalse(jsonValue.has("oneOf"));
        update(admin, grant, map("status", "REVOKED", "version", 0));
        getJson(ROOT + "/metadata", token).andExpect(status().isForbidden());
        update(admin, ADMIN_API + "/endpoints/" + endpoints.get(0) + "/status", map("status", "OFFLINE", "version", 1));
        assertFalse(body(getJson(ADMIN_API + "/docs/v1", admin)).path("paths").has(ROOT));
    }

    private void assertDedicatedPagination(String dedicated) throws Exception {
        var page = body(getJson(ROOT, dedicated, "keyword", "open_field_", "dataType", "TEXT",
                "enable", true, "pageSize", 1));
        assertEquals(2, page.path("total").asLong());
        assertEquals(1, page.path("pageData").size());
        assertEquals(Set.of("id", "config"), Set.copyOf(page.path("pageData").get(0).propertyNames()));
        assertEquals(1, body(getJson(ROOT, dedicated, "keyword", "open_field_", "pageNo", 2, "pageSize", 1))
                .path("pageData").size());
        var empty = body(getJson(ROOT, dedicated, "keyword", "open_field_", "pageNo", 3, "pageSize", 1));
        assertEquals(2, empty.path("total").asLong());
        assertTrue(empty.path("pageData").isEmpty());
        assertEquals(0, body(getJson(ROOT, dedicated, "keyword", "open_field_", "enable", false)).path("total").asLong());
        assertEquals(0, body(getJson(ROOT, dedicated, "keyword", "open_field_", "dataType", "INTEGER")).path("total").asLong());
        assertEquals(0, body(getJson(ROOT, dedicated, "keyword", "open_field_'s")).path("total").asLong());
    }

    private long createField(String admin, String code) throws Exception {
        return data(postJson(EMPLOYEE, admin, map("code", code, "name", "公开字段", "nameEn", "Public Field",
                "dataType", "TEXT", "enable", true, "required", true, "textMaxLength", 100,
                "textCharLimitEnabled", true, "textAllowEnglish", true, "defaultValue", "Example"))).asLong();
    }

    private List<Long> publishDedicated(String admin) throws Exception {
        var ids = new ArrayList<Long>();
        var page = data(getJson(ADMIN_API + "/endpoints", admin, "moduleCode", "field-definition"));
        for (var endpoint : page.path("pageData")) {
            if (endpoint.path("endpointCode").asString().startsWith("field-definition.public.")) {
                long id = endpoint.path("id").asLong();
                update(admin, ADMIN_API + "/endpoints/" + id + "/status", map("status", "PUBLISHED", "version", 0));
                ids.add(id);
            }
        }
        assertEquals(5, ids.size());
        return ids;
    }

    private JsonNode findEndpoint(String admin, String code) throws Exception {
        var endpoints = data(getJson(ADMIN_API + "/endpoints", admin, "keyword", code)).path("pageData");
        assertEquals(1, endpoints.size());
        return endpoints.get(0);
    }

    private long importManagedDetail(String admin) throws Exception {
        var imported = data(postJson(ADMIN_API + "/endpoints/import", admin, map("sourcePath", EMPLOYEE + "/{id}",
                "displayName", "字段配置导入对照", "endpointCode", "field.test.managed.detail", "pathTemplate", MANAGED)));
        long id = imported.path("id").asLong();
        update(admin, ADMIN_API + "/endpoints/" + id + "/status", map("status", "PUBLISHED", "version", 0));
        return id;
    }

    private String applicationToken(String admin, List<Long> endpoints) throws Exception {
        var app = data(postJson(ADMIN_API + "/apps", admin, map("name", "字段开放接口测试")));
        for (long id : endpoints) {
            update(admin, ADMIN_API + "/apps/" + app.path("id").asLong() + "/grants/" + id,
                    map("status", "GRANTED", "version", null));
        }
        return token(admin, app);
    }

    private String token(String admin, JsonNode app) throws Exception {
        var secret = body(postJson(ADMIN_API + "/apps/" + app.path("id").asLong() + "/secrets", admin, null), 201).path("data");
        String credentials = app.path("clientId").asString() + ":" + secret.path("clientSecret").asString();
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.US_ASCII));
        return body(mockMvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "client_credentials")))
                .path("access_token").asString();
    }

    private void update(String admin, String path, Object payload) throws Exception {
        data(mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(payload))));
    }
}
