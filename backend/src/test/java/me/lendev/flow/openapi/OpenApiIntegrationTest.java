package me.lendev.flow.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import me.lendev.flow.openapi.mapper.OpenApiAccessMapper;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"flow.open-api.enabled=true", "flow.open-api.token-app-limit=3",
        "spring.datasource.url=jdbc:h2:mem:flowOpenApiIntegration;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
class OpenApiIntegrationTest extends ApiTestBase {
    private static final String ADMIN_API = "/admin/open-api";
    private static final String MARKETS = "/open/v1/trading-calendar/markets";
    private static final String ENVIRONMENT = "integration-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private OpenApiRateLimits rateLimits;

    @Autowired
    private JdbcClient fixture;

    @Autowired
    private OpenApiAccessMapper accessMapper;

    @Autowired
    private OpenApiProperties settings;

    @DynamicPropertySource
    static void isolateOpenApiRedisKeys(DynamicPropertyRegistry properties) {
        properties.add("flow.open-api.environment", () -> ENVIRONMENT);
    }

    @Test
    void publishesWholeReadContractsAcrossModulesWithOnlyInterfaceGrants() throws Exception {
        String admin = loginAsAdmin();
        assertWholeReadSources(admin);

        String root = "/open/v1/managed/";
        String metadata = root + "field-metadata";
        String field = root + "field-detail";
        String fields = root + "field-entries";
        String dictionaries = root + "field-dictionaries";
        String dictionaryOptions = root + "field-dictionary-options";
        String departments = root + "departments";
        String documents = root + "document-options";
        String markets = root + "todo-market-options";
        long fieldFixture = data(postJson("/field-definition", admin, map("code", "app_read_demo",
                "name", "示例字段", "nameEn", "Example field", "dataType", "TEXT", "enable", true,
                "defaultValue", "Example value"))).asLong();
        long metadataId = publishEndpointOnly(admin, "/field-definition/metadata", metadata, "field.metadata");
        long fieldId = publishEndpointOnly(admin, "/field-definition/{id}", field, "field.detail");
        long fieldsId = publishEndpointOnly(admin, "/field-definition", fields, "field.entries");
        long dictionariesId = publishEndpointOnly(admin, "/field-definition/dictionaries", dictionaries, "field.dictionaries");
        long dictionaryOptionsId = publishEndpointOnly(admin, "/field-definition/dictionaries/{id}/options",
                dictionaryOptions, "field.dictionary-options");
        long departmentsId = publishEndpointOnly(admin, "/department", departments, "department.tree");
        long documentsId = publishEndpointOnly(admin, "/documents/options", documents, "document.options");
        long marketsId = publishEndpointOnly(admin, "/workbench/todos/markets", markets, "todo.market-options");
        var catalogClient = endpointOnlyClient(admin, "字段目录应用", metadataId, fieldId, fieldsId, dictionariesId, dictionaryOptionsId);
        var organizationClient = endpointOnlyClient(admin, "部门目录应用", departmentsId, documentsId, marketsId);

        var employeeMetadata = data(getJson("/field-definition/metadata", admin));
        assertEquals(employeeMetadata, body(getJson(metadata, catalogClient.token())));
        assertTrue(employeeMetadata.path("types").get(0).has("name"));
        var employeeDetail = data(getJson("/field-definition/" + fieldFixture, admin));
        assertEquals(employeeDetail, body(getJson(field, catalogClient.token(), "id", fieldFixture)));
        assertEquals("Example value", employeeDetail.path("config").path("defaultValue").asString());
        var employeePage = data(getJson("/field-definition", admin, "dataType", "TEXT", "keyword", "app_read_demo"));
        assertEquals(employeePage, body(getJson(fields, catalogClient.token(), "dataType", "TEXT", "keyword", "app_read_demo")));
        getJson(fields, catalogClient.token(), "pageSize", 101).andExpect(status().isBadRequest());
        var employeeDictionaries = data(getJson("/field-definition/dictionaries", admin));
        assertEquals(employeeDictionaries, body(getJson(dictionaries, catalogClient.token())));
        long dictionary = employeeDictionaries.get(0).path("id").asLong();
        assertEquals(data(getJson("/field-definition/dictionaries/" + dictionary + "/options", admin)),
                body(getJson(dictionaryOptions, catalogClient.token(), "id", dictionary)));
        getJson(dictionaries, catalogClient.token(), "keyword", "a".repeat(101)).andExpect(status().isBadRequest());
        getJson(field, catalogClient.token(), "id", 0).andExpect(status().isBadRequest());
        assertEquals(data(getJson("/department", admin)), body(getJson(departments, organizationClient.token())));
        assertEquals(data(getJson("/documents/options", admin)), body(getJson(documents, organizationClient.token())));
        assertEquals(data(getJson("/workbench/todos/markets", admin)), body(getJson(markets, organizationClient.token())));
        assertContractDocumentationAndGrants(admin, metadata, field, departments, catalogClient, organizationClient);
    }

    @Test
    void opensSevenManagementModulesThroughTheSameApplicationGrants() throws Exception {
        String admin = loginAsAdmin();
        var modules = List.of(
                new ManagementSource("field-definition", "/field-definition/metadata", "management-field-metadata"),
                new ManagementSource("param", "/param/catalog/groups", "management-param-groups"),
                new ManagementSource("department", "/department", "management-departments"),
                new ManagementSource("user", "/user/directory/entries", "management-users"),
                new ManagementSource("role", "/role/catalog", "management-roles"),
                new ManagementSource("permission", "/permission/catalog", "management-permissions"),
                new ManagementSource("document", "/documents/options", "management-document-options"));
        var sources = data(getJson(ADMIN_API + "/endpoint-sources", admin, "importable", true));
        for (var module : modules) {
            var source = find(sources, "sourcePath", module.source());
            assertEquals(module.module(), source.path("moduleCode").asString(), module.source());
            assertTrue(source.path("wholeResponse").asBoolean(), module.source());
        }
        var internalRoutes = Set.of("/param/options/{code}", "/user", "/user/detail", "/role/permissions",
                "/permission/tree", "/documents", "/documents/{id}", "/documents/{id}/download");
        for (var source : sources) {
            assertFalse(internalRoutes.contains(source.path("sourcePath").asString()));
        }

        var endpointIds = new long[modules.size()];
        for (int index = 0; index < modules.size(); index++) {
            var module = modules.get(index);
            endpointIds[index] = publishEndpointOnly(admin, module.source(), module.path(), module.scope());
        }
        var directory = endpointOnlyClient(admin, "管理目录接入", endpointIds);
        var metadataOnly = endpointOnlyClient(admin, "字段目录接入", endpointIds[0]);
        var paths = body(getJson(ADMIN_API + "/docs/v1", admin)).path("paths");
        for (var module : modules) {
            assertEquals(data(getJson(module.source(), admin)), body(getJson(module.path(), directory.token())), module.source());
            var operation = paths.path(module.path()).path("get");
            assertEquals(module.scope(), operation.path("x-required-scope").asString());
            assertEquals("Endpoint unavailable or resource not found", operation.path("responses").path("404")
                    .path("description").asString());
        }
        getJson(modules.get(0).path(), metadataOnly.token()).andExpect(status().isOk());
        getJson(modules.get(3).path(), metadataOnly.token()).andExpect(status().isForbidden());
        getJson(modules.get(3).source(), directory.token()).andExpect(status().isUnauthorized());
        data(putJson(directory.grantPath(), admin, map("status", "REVOKED", "version", 0)));
        getJson(modules.get(0).path(), directory.token()).andExpect(status().isForbidden());
        getJson(modules.get(0).path(), metadataOnly.token()).andExpect(status().isOk());
    }

    private record ManagementSource(String module, String source, String slug) {
        String path() {
            return "/open/v1/managed/" + slug;
        }

        String scope() {
            return "management." + module + ".read";
        }
    }

    private void assertWholeReadSources(String admin) throws Exception {
        String sourcesPath = ADMIN_API + "/endpoint-sources";
        var sources = data(getJson(sourcesPath, admin, "importable", true));
        for (String route : List.of("/field-definition", "/field-definition/{id}", "/field-definition/metadata",
                "/field-definition/dictionaries", "/field-definition/dictionaries/{id}/options", "/department",
                "/documents/options", "/workbench/todos/markets")) {
            var source = find(sources, "sourcePath", route);
            assertEquals("NONE", source.path("dataScopeType").asString());
            assertTrue(source.path("wholeResponse").asBoolean());
            assertTrue(source.path("fields").isEmpty());
        }
        var employeeOnly = Set.of("/documents", "/documents/{id}", "/workbench/todos", "/department/members",
                "/department/users/{userId}/memberships", "/auth/refresh/token", "/param/options/{code}");
        for (var source : sources) {
            assertFalse(employeeOnly.contains(source.path("sourcePath").asString()));
        }
    }

    private void assertContractDocumentationAndGrants(String admin, String metadata, String field, String departments,
                                                      ManagedClient catalogClient, ManagedClient organizationClient) throws Exception {
        getJson(metadata, organizationClient.token()).andExpect(status().isForbidden());
        getJson(departments, catalogClient.token()).andExpect(status().isForbidden());
        getJson("/department", organizationClient.token()).andExpect(status().isUnauthorized());
        var paths = body(getJson(ADMIN_API + "/docs/v1", admin)).path("paths");
        var operation = paths.path(metadata).path("get");
        var schema = operation.path("responses").path("200").path("content").path("application/json").path("schema");
        assertTrue(schema.path("properties").path("types").has("items"));
        var detailRequired = paths.path(field).path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema").path("required");
        assertTrue(StreamSupport.stream(detailRequired.spliterator(), false).anyMatch(property -> "id".equals(property.asString())));
        data(putJson(catalogClient.grantPath(), admin, map("status", "REVOKED", "version", 0)));
        getJson(metadata, catalogClient.token()).andExpect(status().isForbidden());
    }

    private long publishEndpointOnly(String admin, String source, String path, String code) throws Exception {
        String endpoints = ADMIN_API + "/endpoints";
        var imported = data(postJson(endpoints + "/import", admin, map("sourcePath", source, "displayName", code,
                "endpointCode", code, "pathTemplate", path)));
        assertEquals("NONE", imported.path("dataScopeType").asString());
        long id = imported.path("id").asLong();
        data(putJson(endpoints + "/" + id + "/status", admin, map("status", "PUBLISHED", "version", 0)));
        return id;
    }

    private ManagedClient endpointOnlyClient(String admin, String name, long... endpoints) throws Exception {
        var app = data(postJson(ADMIN_API + "/apps", admin, map("name", name)));
        long appId = app.path("id").asLong();
        String appPath = ADMIN_API + "/apps/" + appId;
        for (long id : endpoints) {
            data(putJson(appPath + "/grants/" + id, admin,
                    map("status", "GRANTED", "version", null)));
        }
        String secret = body(postJson(appPath + "/secrets", admin, null), 201).path("data").path("clientSecret").asString();
        String clientId = app.path("clientId").asString();
        String token = body(exchange(clientId, secret)).path("access_token").asString();
        return new ManagedClient(appId, appPath + "/grants/" + endpoints[0], token, clientId, secret);
    }

    @Test
    void existingInstitutionGrantsReturnAllMatchingRecordsForEachApplication() throws Exception {
        String admin = loginAsAdmin();
        String detailPath = "/open/v1/managed/institution-detail";
        String listPath = "/open/v1/managed/institution-list";
        var options = data(getJson("/institutions/options", admin));
        long firstId = institutionFixture(admin, options, "APP-NORTH");
        long secondId = institutionFixture(admin, options, "APP-SOUTH");
        data(putJson("/institutions/" + secondId + "/status", admin, map("enabled", false, "version", 0)));
        var sources = data(getJson(ADMIN_API + "/endpoint-sources", admin));
        var listSource = find(sources, "sourcePath", "/institutions");
        var detailSource = find(sources, "sourcePath", "/institutions/{id}");
        assertTrue(listSource.path("importable").asBoolean());
        assertTrue(listSource.path("page").asBoolean());
        assertTrue(detailSource.path("importable").asBoolean());
        assertTrue(find(detailSource.path("parameters"), "name", "id").path("required").asBoolean());
        assertEquals("string", find(detailSource.path("fields"), "name", "code").path("type").asString());
        long listId = publishInstitution(admin, "/institutions", listPath, "institution.list");
        long detailId = publishInstitution(admin, "/institutions/{id}", detailPath, "institution.detail");
        var first = endpointOnlyClient(admin, "机构应用 A", detailId, listId);
        var second = endpointOnlyClient(admin, "机构应用 B", detailId, listId);
        for (long endpointId : List.of(listId, detailId)) {
            restrictLegacyGrant(first.appId(), endpointId, "institutions", firstId, "APP-NORTH");
            restrictLegacyGrant(second.appId(), endpointId, "institutions", secondId, "APP-SOUTH");
        }

        var firstPage = body(getJson(listPath, first.token(), "keyword", "APP-", "pageSize", "1"));
        assertEquals(2, firstPage.path("total").asLong());
        assertEquals("APP-SOUTH", firstPage.path("pageData").get(0).path("code").asString());
        assertEquals(2, firstPage.path("pageData").get(0).size());
        assertEquals("APP-SOUTH", body(getJson(listPath, second.token())).path("pageData").get(0).path("code").asString());
        assertEquals(1, body(getJson(listPath, first.token(), "enabled", false)).path("total").asLong());
        assertEquals("APP-NORTH", body(getJson(detailPath, first.token(), "id", firstId)).path("code").asString());
        assertEquals("APP-SOUTH", body(getJson(detailPath, first.token(), "id", secondId)).path("code").asString());
        assertEquals("APP-NORTH", body(getJson(detailPath, second.token(), "id", firstId)).path("code").asString());
        getJson(detailPath, first.token(), "id", Long.MAX_VALUE).andExpect(status().isNotFound());
        getJson(detailPath, first.token()).andExpect(status().isBadRequest());
        getJson(listPath, first.token(), "pageSize", 101).andExpect(status().isBadRequest());
        getJson("/institutions/" + firstId, first.token()).andExpect(status().isUnauthorized());
        getJson("/institutions/" + firstId, admin).andExpect(status().isOk());

        var documented = body(getJson(ADMIN_API + "/docs/v1", admin)).path("paths").path(listPath).path("get");
        var pageSchema = documented.path("responses").path("200").path("content").path("application/json").path("schema");
        assertEquals("array", pageSchema.path("properties").path("pageData").path("type").asString());
        assertTrue(pageSchema.path("properties").has("total"));
        data(putJson(first.grantPath(), admin, map("status", "REVOKED", "version", 0)));
        getJson(detailPath, first.token(), "id", firstId).andExpect(status().isForbidden());
    }

    private long institutionFixture(String admin, JsonNode options, String code) throws Exception {
        return data(postJson("/institutions", admin, map("code", code, "nameZh", code,
                "typeItemId", find(options.path("types"), "code", "BANK").path("id").asLong(),
                "countryRegionItemId", find(options.path("countries"), "code", "CN").path("id").asLong()))).path("id").asLong();
    }

    private long publishInstitution(String admin, String source, String path, String code) throws Exception {
        String endpoints = ADMIN_API + "/endpoints";
        var imported = data(postJson(endpoints + "/import", admin, map("sourcePath", source,
                "displayName", code, "endpointCode", code, "pathTemplate", path, "responseFields", List.of("id", "code"))));
        assertEquals("NONE", imported.path("dataScopeType").asString());
        long id = imported.path("id").asLong();
        data(putJson(endpoints + "/" + id + "/status", admin, map("status", "PUBLISHED", "version", 0)));
        return id;
    }

    @Test
    void discoversDeployedRestRoutesAndPublishesAnExistingNonCalendarRead() throws Exception {
        String admin = loginAsAdmin();
        String endpoints = ADMIN_API + "/endpoints";
        String sourcePath = "/workbench/todos/public-dimensions";
        String publicPath = "/open/v1/managed/todo-dimensions";
        var sources = data(getJson(ADMIN_API + "/endpoint-sources", admin));
        assertTrue(find(sources, "sourcePath", sourcePath).path("importable").asBoolean());
        assertEquals("POLICY_REQUIRED", find(sources, "sourcePath", "/audit/logs/{id}").path("reason").asString());
        assertEquals("READ_ONLY", find(sources, "sourcePath", "/trading-calendar/initialize/apply").path("reason").asString());
        for (var source : sources) {
            assertFalse(source.path("sourcePath").asString().startsWith("/open/"));
        }
        var created = data(postJson(endpoints + "/import", admin, map("sourcePath", sourcePath,
                "displayName", "待办维度", "endpointCode", "managed.todo.dimensions", "pathTemplate", publicPath,
                "responseFields", List.of("slot", "name"))));
        assertEquals("NONE", created.path("dataScopeType").asString());
        long endpointId = created.path("id").asLong();
        data(putJson(endpoints + "/" + endpointId + "/status", admin, map("status", "PUBLISHED", "version", 0)));
        var client = endpointOnlyClient(admin, "待办维度应用", endpointId);
        var result = body(getJson(publicPath, client.token()));
        assertTrue(result.isArray());
        assertFalse(result.isEmpty());
        assertEquals(2, result.get(0).size());
        assertTrue(result.get(0).has("slot"));
        assertFalse(result.get(0).has("options"));
        getJson(sourcePath, client.token()).andExpect(status().isUnauthorized());
    }

    @Test
    void legacyMappedCalendarReturnsAllMarketsAndKeepsPublishedContract() throws Exception {
        String admin = loginAsAdmin();
        String endpoints = ADMIN_API + "/endpoints";
        var sources = data(getJson(ADMIN_API + "/endpoint-sources", admin));
        assertEquals("GET", find(sources, "sourcePath", "/trading-calendar/markets").path("httpMethod").asString());
        String publicPath = "/open/v1/managed/calendar-markets";
        var created = data(postJson(endpoints + "/import", admin, map("sourcePath", "/trading-calendar/markets",
                "displayName", "配置开放市场", "endpointCode", "managed.calendar.markets", "pathTemplate", publicPath,
                "responseFields", List.of("code"))));
        String endpointPath = endpoints + "/" + created.path("id").asLong();
        assertEquals("REGISTERED", created.path("status").asString());
        assertEquals("/trading-calendar/markets", created.path("source").path("sourcePath").asString());
        long endpointId = created.path("id").asLong();
        restoreLegacyMarketContract(endpointId);
        var published = data(putJson(endpointPath + "/status", admin, map("status", "PUBLISHED", "version", 0)));
        var first = endpointOnlyClient(admin, "市场应用 A", endpointId);
        var second = endpointOnlyClient(admin, "市场应用 B", endpointId);
        restrictLegacyGrant(first.appId(), endpointId, "markets", marketId("CN"), "CN");
        restrictLegacyGrant(second.appId(), endpointId, "markets", marketId("HK"), "HK");

        var firstResult = body(getJson(publicPath, first.token()));
        assertEquals("HK", find(firstResult, "code", "HK").path("code").asString());
        assertEquals(1, firstResult.get(0).size());
        assertEquals(firstResult, body(getJson(publicPath, second.token())));
        getJson(publicPath, first.token(), "unused", "value").andExpect(status().isBadRequest());
        getJson("/trading-calendar/markets", first.token()).andExpect(status().isUnauthorized());
        mockMvc.perform(head(publicPath).header(HttpHeaders.AUTHORIZATION, "Bearer " + first.token()))
                .andExpect(status().isNotFound());
        var docs = body(getJson(ADMIN_API + "/docs/v1", admin));
        var operation = docs.path("paths").path(publicPath).path("get");
        assertEquals("managed.calendar.markets", operation.path("x-required-scope").asString());
        assertFalse(operation.has("operationId"));

        data(putJson(first.grantPath(), admin, map("status", "REVOKED", "version", 0)));
        getJson(publicPath, first.token()).andExpect(status().isForbidden());
        fixture.sql("UPDATE sys_open_api_read_mapping SET source_contract_hash = :hash WHERE endpoint_id = :id")
                .param("hash", "0".repeat(64)).param("id", endpointId).update();
        getJson(publicPath, second.token()).andExpect(status().isNotFound());
        exchange(second.clientId(), second.secret()).andExpect(status().isBadRequest());
        assertFalse(body(getJson(ADMIN_API + "/docs/v1", admin)).path("paths").has(publicPath));
        var offline = data(putJson(endpointPath + "/status", admin,
                map("status", "OFFLINE", "version", published.path("version").asLong())));
        var confirmed = data(putJson(endpointPath + "/source-contract", admin,
                map("version", offline.path("version").asLong())));
        assertTrue(confirmed.path("source").path("contractMatches").asBoolean());
        data(putJson(endpointPath + "/status", admin,
                map("status", "PUBLISHED", "version", confirmed.path("version").asLong())));
        assertEquals(firstResult, body(getJson(publicPath, second.token())));
    }

    private void restoreLegacyMarketContract(long endpointId) {
        fixture.sql("""
                        UPDATE sys_open_api_read_mapping
                        SET public_contract_json = :contract, source_contract_hash = :hash
                        WHERE endpoint_id = :id
                        """).param("contract", """
                        {"parameters":[],"fields":[{"name":"code","type":"string","nullable":true,"format":null}],
                        "collection":true,"dataScopeType":"MARKET","scopePolicyVersion":"trading-calendar-market-v1"}
                        """).param("hash", "40610f42a2258c8c46c6337002c2227da3bedc26b4da8c7d4d2086a649feb469")
                .param("id", endpointId).update();
    }

    private record ManagedClient(long appId, String grantPath, String token, String clientId, String secret) {
    }

    @Test
    void managedReadBindsSourceQueryAndRejectsNonPublicContracts() throws Exception {
        String admin = loginAsAdmin();
        String endpoints = ADMIN_API + "/endpoints";
        String importPath = endpoints + "/import";
        String path = "/open/v1/managed/calendar-day";
        postJson(importPath, admin, map("sourcePath", "/trading-calendar/initialize/apply", "displayName", "初始化",
                "endpointCode", "managed.calendar.init", "pathTemplate", path, "responseFields", List.of("code")))
                .andExpect(status().isBadRequest());
        postJson(importPath, admin, map("sourcePath", "/trading-calendar/status", "displayName", "日期",
                "endpointCode", "managed.calendar.day", "pathTemplate", path, "responseFields", List.of("calendarDate8")))
                .andExpect(status().isBadRequest());
        var request = map("sourcePath", "/trading-calendar/status", "displayName", "日期",
                "endpointCode", "managed.calendar.day", "pathTemplate", path, "responseFields", List.of("calendarDate", "isTradingDay"));
        var endpoint = data(postJson(importPath, admin, request));
        postJson(importPath, admin, request).andExpect(status().isConflict());
        data(putJson(endpoints + "/" + endpoint.path("id").asLong() + "/status", admin, map("status", "PUBLISHED", "version", 0)));
        var client = endpointOnlyClient(admin, "日历状态只读", endpoint.path("id").asLong());
        fixture.sql("""
                INSERT INTO sys_trading_calendar_day (marketItemId, calendarDate, calendarDate8, isTradingDay, source)
                VALUES (:market, DATE '2027-01-04', '20270104', TRUE, 'INIT')
                """).param("market", marketId("CN")).update();
        var day = body(getJson(path, client.token(), "marketCode", "CN", "date", "2027-01-04"));
        assertEquals("2027-01-04", day.path("calendarDate").asString());
        assertTrue(day.path("isTradingDay").asBoolean());
        assertEquals(2, day.size());
        getJson(path, client.token(), "marketCode", "HK", "date", "2027-01-04").andExpect(status().isNotFound());
        getJson(path, client.token(), "marketCode", "CN").andExpect(status().isBadRequest());
        getJson(path, client.token(), "marketCode", "CN", "date", "2027-02-30").andExpect(status().isBadRequest());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + client.token())
                        .queryParam("marketCode", "CN", "HK").queryParam("date", "2027-01-04"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + client.token())
                        .queryParam("marketCode", "CN").queryParam("date", "2027-01-04")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + client.token()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void publicRouteChallengesForApplicationToken() throws Exception {
        mockMvc.perform(get(MARKETS))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        getJson(MARKETS, loginAsAdmin()).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .formField("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_XML).formField("grant_type", "client_credentials"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void tokenEndpointOnlyAcceptsPost() throws Exception {
        mockMvc.perform(options("/oauth2/token"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(head(MARKETS)).andExpect(status().isNotFound());
        mockMvc.perform(get("/open/v1/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void tokenRequestsValidateTheFormAndRejectInvalidCredentialsOrScopes() throws Exception {
        String tokenPath = "/oauth2/token";
        assertEquals("invalid_request", body(mockMvc.perform(post(tokenPath)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)), 400).path("error").asString());
        assertEquals("unsupported_grant_type", body(mockMvc.perform(post(tokenPath)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "refresh_token")), 400)
                .path("error").asString());
        mockMvc.perform(post(tokenPath).queryParam("grant_type", "client_credentials")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)).andExpect(status().isBadRequest());
        mockMvc.perform(post(tokenPath).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .formField("grant_type", "client_credentials", "client_credentials")).andExpect(status().isBadRequest());
        for (String invalidBasic : List.of("Basic ???", "Basic Zm9v", "Basic Zm9vOmJhcg==")) {
            mockMvc.perform(post(tokenPath).header(HttpHeaders.AUTHORIZATION, invalidBasic)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "client_credentials"))
                    .andExpect(status().isUnauthorized());
        }
        String admin = loginAsAdmin();
        JsonNode app = data(postJson(ADMIN_API + "/apps", admin, map("name", "Unpublished authorization")));
        JsonNode secret = body(postJson(ADMIN_API + "/apps/" + app.path("id").asLong() + "/secrets", admin, null), 201)
                .path("data");
        assertEquals("invalid_scope", body(exchange(app.path("clientId").asString(), secret.path("clientSecret").asString()), 400)
                .path("error").asString());
    }

    @Test
    void scopesAndRevocationApplyToExistingTokens() throws Exception {
        String admin = loginAsAdmin();
        JsonNode endpoints = data(getJson(ADMIN_API + "/endpoints", admin));
        JsonNode endpoint = find(endpoints.get("pageData"), "endpointCode", "trading-calendar.markets.read");
        long endpointId = endpoint.get("id").asLong();
        data(putJson(ADMIN_API + "/endpoints/" + endpointId + "/status", admin,
                map("status", "PUBLISHED", "version", endpoint.get("version").asLong())));
        JsonNode statusEndpoint = find(endpoints.get("pageData"), "endpointCode", "trading-calendar.status.read");
        data(putJson(ADMIN_API + "/endpoints/" + statusEndpoint.get("id").asLong() + "/status", admin,
                map("status", "PUBLISHED", "version", statusEndpoint.get("version").asLong())));
        JsonNode app = data(postJson(ADMIN_API + "/apps", admin, map("name", "报表应用")));
        long appId = app.get("id").asLong();
        String appPath = ADMIN_API + "/apps/" + appId;
        String grantPath = appPath + "/grants/" + endpointId;
        JsonNode grant = data(putJson(grantPath, admin, map("status", "GRANTED", "dataScopeMode", "ALL", "version", null)));
        restrictLegacyGrant(appId, endpointId, "markets", marketId("CN"), "CN");
        JsonNode candidate = body(postJson(appPath + "/secrets", admin, null), 201).get("data");
        String clientId = app.get("clientId").asString();
        String secret = candidate.get("clientSecret").asString();
        String secretPath = appPath + "/secrets/" + candidate.get("versionNo").asInt();
        for (int attempt = 0; attempt < 4; attempt++) {
            exchange(clientId, OpenApiCredentials.newSecret()).andExpect(status().isUnauthorized());
        }
        var first = body(exchange(clientId, secret));
        String token = first.get("access_token").asString();
        assertEquals("trading-calendar.markets.read", first.get("scope").asString());
        assertFalse(first.has("refresh_token"));
        data(postJson(secretPath + "/activate", admin, null));
        String otherToken = body(exchange(clientId, secret)).get("access_token").asString();
        assertNotEquals(token, otherToken);
        assertEquals("HK", find(body(getJson(MARKETS, token)), "code", "HK").path("code").asString());
        assertEquals(body(getJson(MARKETS, token)), body(getJson(MARKETS, otherToken)));
        getJson("/open/v1/trading-calendar/markets", "fat_" + "a".repeat(43)).andExpect(status().isUnauthorized());
        fixture.sql("UPDATE sys_open_api_app_grant SET data_scope_json = '[]' WHERE app_id = :id").param("id", appId).update();
        getJson(MARKETS, otherToken).andExpect(status().isOk());
        getJson(ADMIN_API + "/apps", token).andExpect(status().isUnauthorized());
        getJson("/open/v1/trading-calendar/status", token, "marketCode", "CN", "date", "2026-11-20")
                .andExpect(status().isForbidden());
        JsonNode withdrawn = data(putJson(grantPath, admin, map("status", "REVOKED", "version", grant.get("version").asLong())));
        getJson(MARKETS, otherToken).andExpect(status().isForbidden());
        data(putJson(grantPath, admin, map("status", "GRANTED", "dataScopeMode", "ALL", "version", withdrawn.get("version").asLong())));
        getJson(MARKETS, otherToken).andExpect(status().isOk());

        JsonNode disabled = data(putJson(appPath + "/status", admin, map("enabled", false, "version", app.get("version").asLong())));
        getJson(MARKETS, otherToken).andExpect(status().isUnauthorized());
        data(putJson(appPath + "/status", admin, map("enabled", true, "version", disabled.get("version").asLong())));
        getJson(MARKETS, otherToken).andExpect(status().isUnauthorized());
        String afterEnabled = body(exchange(clientId, secret)).get("access_token").asString();
        getJson(MARKETS, afterEnabled).andExpect(status().isOk());
        replaceClientId(appId, OpenApiCredentials.newClientId());
        getJson(MARKETS, afterEnabled).andExpect(status().isUnauthorized());
        replaceClientId(appId, clientId);
        data(postJson(secretPath + "/revoke", admin, map("mode", "COMPROMISED")));
        getJson(MARKETS, afterEnabled).andExpect(status().isUnauthorized());
        exchange(clientId, secret).andExpect(status().isUnauthorized());
    }

    private long marketId(String code) {
        return fixture.sql("""
                SELECT i.id FROM sys_param_item i JOIN sys_param_group g ON g.id = i.groupId
                WHERE g.code = 'TRADING_MARKET' AND i.code = :code
                """).param("code", code).query(Long.class).single();
    }

    private void restrictLegacyGrant(long appId, long endpointId, String collection, long resourceId, String code) {
        var scope = "{\"%s\":[{\"id\":%d,\"code\":\"%s\"}]}".formatted(collection, resourceId, code);
        fixture.sql("""
                UPDATE sys_open_api_app_grant SET data_scope_mode = 'RESTRICTED', data_scope_json = :scope
                WHERE app_id = :appId AND endpoint_id = :endpointId
                """).param("scope", scope).param("appId", appId).param("endpointId", endpointId).update();
    }

    @Test
    void ordinaryRotationRetainsOldTokensAndEmergencyRevocationRemovesOnlyTheCompromisedVersion() throws Exception {
        String admin = loginAsAdmin();
        JsonNode market = find(data(getJson(ADMIN_API + "/endpoints", admin)).get("pageData"),
                "endpointCode", "trading-calendar.markets.read");
        long endpointId = market.get("id").asLong();
        data(putJson(ADMIN_API + "/endpoints/" + endpointId + "/status", admin,
                map("status", "PUBLISHED", "version", market.get("version").asLong())));
        JsonNode app = data(postJson(ADMIN_API + "/apps", admin, map("name", "轮转演练")));
        String appPath = ADMIN_API + "/apps/" + app.get("id").asLong();
        String clientId = app.get("clientId").asString();
        data(putJson(appPath + "/grants/" + endpointId, admin,
                map("status", "GRANTED", "dataScopeMode", "ALL", "version", null)));
        JsonNode first = body(postJson(appPath + "/secrets", admin, null), 201).get("data");
        String oldSecret = first.get("clientSecret").asString();
        String oldPath = appPath + "/secrets/" + first.get("versionNo").asInt();
        data(postJson(oldPath + "/activate", admin, null));
        String oldToken = body(exchange(clientId, oldSecret)).get("access_token").asString();

        JsonNode replacement = body(postJson(appPath + "/secrets", admin, null), 201).get("data");
        String newSecret = replacement.get("clientSecret").asString();
        String newPath = appPath + "/secrets/" + replacement.get("versionNo").asInt();
        String newToken = body(exchange(clientId, newSecret)).get("access_token").asString();
        fixture.sql("UPDATE sys_open_api_app_secret SET expires_at = :expiry WHERE app_id = :appId AND version_no = :version")
                .param("expiry", LocalDateTime.of(2000, 1, 1, 0, 0)).param("appId", app.path("id").asLong())
                .param("version", replacement.path("versionNo").asInt()).update();
        getJson(MARKETS, newToken).andExpect(status().isUnauthorized());
        fixture.sql("UPDATE sys_open_api_app_secret SET expires_at = :expiry WHERE app_id = :appId AND version_no = :version")
                .param("expiry", LocalDateTime.of(2099, 1, 1, 0, 0)).param("appId", app.path("id").asLong())
                .param("version", replacement.path("versionNo").asInt()).update();
        data(postJson(newPath + "/activate", admin, null));
        exchange(clientId, oldSecret).andExpect(status().isOk());
        data(postJson(oldPath + "/revoke", admin, map("mode", "RETIRE")));
        exchange(clientId, oldSecret).andExpect(status().isUnauthorized());
        getJson(MARKETS, oldToken).andExpect(status().isOk());

        data(postJson(oldPath + "/revoke", admin, map("mode", "COMPROMISED")));
        getJson(MARKETS, oldToken).andExpect(status().isUnauthorized());
        getJson(MARKETS, newToken).andExpect(status().isOk());
    }

    @Test
    void deniedEndpointQuotaDoesNotConsumeApplicationQuota() {
        rateLimits.business(777, 2, 86400, 1, 1, 86400);
        var endpointBusy = assertThrows(OpenApiException.class, () -> rateLimits.business(777, 2, 86400, 1, 1, 86400));
        assertEquals(429, endpointBusy.status());
        rateLimits.business(777, 2, 86400, 2, 1, 86400);
        var appBusy = assertThrows(OpenApiException.class, () -> rateLimits.business(777, 2, 86400, 3, null, null));
        assertEquals(429, appBusy.status());
    }

    @Test
    void writesIdentifiedCallsAndPrunesExpiredMetadata() {
        var calls = new OpenApiCalls(accessMapper, settings);
        var recognized = new OpenApiCalls.Context("open-api-kept", "127.0.0.1");
        recognized.application(3, "fixture-client");
        recognized.endpoint(1L, "trading-calendar.markets.read");
        recognized.failed("RATE_LIMITED");
        calls.finish(recognized, 429);
        calls.finish(new OpenApiCalls.Context("open-api-anonymous", "127.0.0.1"), 401);
        calls.flush();

        var old = new OpenApiAccessMapper.Call("open-api-expired", 3, "fixture-client", 1L, "trading-calendar.markets.read",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(31), 2, 200, null, null);
        accessMapper.insertCalls(List.of(old));
        calls.removeExpired();
        var entries = fixture.sql("SELECT request_id FROM sys_open_api_call_log WHERE request_id LIKE 'open-api-%'")
                .query(String.class).list();
        assertEquals(List.of("open-api-kept"), entries);
        assertEquals("RATE_LIMITED", fixture.sql("SELECT error_code FROM sys_open_api_call_log WHERE request_id = 'open-api-kept'")
                .query(String.class).single());
    }

    private ResultActions putJson(String path, String admin, Object value) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(value)));
    }

    private void replaceClientId(long id, String clientId) {
        fixture.sql("UPDATE sys_open_api_app SET client_id = :clientId WHERE id = :id")
                .param("clientId", clientId).param("id", id).update();
    }

    private ResultActions exchange(String clientId, String secret) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.US_ASCII));
        return mockMvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "client_credentials"));
    }

    private static JsonNode find(JsonNode values, String field, String value) {
        for (JsonNode row : values) {
            if (value.equals(row.get(field).asString())) {
                return row;
            }
        }
        throw new AssertionError("未找到 " + field + "=" + value);
    }
}
