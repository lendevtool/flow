package me.lendev.flow.pms.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 验证用户目录的来源发现、应用接口授权和对外字段边界。
 */
@TestPropertySource(properties = {"flow.open-api.enabled=true", "flow.open-api.environment=open-user-directory-test",
        "spring.datasource.url=jdbc:h2:mem:openUserDirectory;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
class OpenUserDirectoryApiTest extends ApiTestBase {

    private static final String ADMIN_API = "/admin/open-api";
    private static final String SOURCE = "/user/directory/entries";
    private static final String PAGE = "/open/v1/managed/user-directory";
    private static final String DETAIL = "/open/v1/managed/user-directory-detail";
    private static final String VIEWER = "directory_viewer";

    @Autowired
    private JdbcClient fixture;

    @Test
    void grantedDirectoryExposesOnlyBusinessFieldsAndFilters() throws Exception {
        String admin = loginAsAdmin();
        long departmentId = department();
        long first = user("directory_private_username", "目录查询", "Directory O'Lane_Unit", true);
        long second = user("directory_other_username", "目录查询", null, false);
        fixture.sql("INSERT INTO sys_department_member (user_id, department_id) VALUES (?, ?)")
                .params(first, departmentId).update();
        fixture.sql("UPDATE `user` SET departmentId = ? WHERE id = ?").params(departmentId, first).update();

        long page = publish(admin, SOURCE, PAGE, "user.directory.page");
        long detail = publish(admin, SOURCE + "/{id}", DETAIL, "user.directory.detail");
        var app = data(postJson(ADMIN_API + "/apps", admin, map("name", "人员目录应用")));
        String appPath = ADMIN_API + "/apps/" + app.path("id").asLong();
        String clientId = app.path("clientId").asString();
        String secret = body(postJson(appPath + "/secrets", admin, null), 201).path("data").path("clientSecret").asString();
        configure(admin, appPath + "/grants/" + page, map("status", "GRANTED"));

        String pageOnly = exchange(clientId, secret);
        getJson(DETAIL, pageOnly, "id", first).andExpect(status().isForbidden());
        configure(admin, appPath + "/grants/" + detail, map("status", "GRANTED"));
        String token = exchange(clientId, secret);

        var firstPage = body(getJson(PAGE, token, "keyword", "目录查询", "pageSize", 1));
        assertEquals(2, firstPage.path("total").asLong());
        assertEquals(1, firstPage.path("pageData").size());
        var directoryUser = firstPage.path("pageData").get(0);
        assertEquals(6, directoryUser.size());
        assertEquals(first, directoryUser.path("id").asLong());
        assertEquals("目录查询", directoryUser.path("nameCn").asString());
        assertEquals("Directory O'Lane_Unit", directoryUser.path("nameEn").asString());
        assertTrue(directoryUser.path("enabled").asBoolean());
        assertEquals(departmentId, directoryUser.path("departmentId").asLong());
        assertEquals("查询目录部门", directoryUser.path("departmentName").asString());
        assertEquals(directoryUser, body(getJson(DETAIL, token, "id", first)));
        assertEquals(directoryUser, data(getJson(SOURCE + "/" + first, admin)));
        assertEquals(second, body(getJson(PAGE, token, "keyword", "目录查询", "pageSize", 1, "pageNo", 2))
                .path("pageData").get(0).path("id").asLong());
        assertEquals(second, body(getJson(PAGE, token, "keyword", "目录查询", "enabled", false))
                .path("pageData").get(0).path("id").asLong());
        assertEquals(1, body(getJson(PAGE, token, "departmentId", departmentId)).path("total").asLong());
        assertEquals(1, body(getJson(PAGE, token, "keyword", "o'lane_unit")).path("total").asLong());
        assertEquals(0, body(getJson(PAGE, token, "keyword", "directory_private_username")).path("total").asLong());
        assertEquals(0, body(getJson(PAGE, token, "keyword", "%")).path("total").asLong());
        assertDirectoryAccessBoundaries(token);
    }

    @Test
    void discoversOnlyDirectorySourcesAndPreservesEmployeeAccessRequirements() throws Exception {
        String admin = loginAsAdmin();
        code(getJson("/user/directory", admin), 11006);
        long viewerId = user(VIEWER, null, null, true);
        fixture.sql("INSERT INTO user_roles_role (userId, roleId) VALUES (?, 2)").param(viewerId).update();
        String viewer = login(VIEWER, ADMIN_PASSWORD);
        code(getJson(SOURCE, viewer), 11003);
        code(getJson(SOURCE + "/" + viewerId, viewer), 11003);

        var sources = data(getJson(ADMIN_API + "/endpoint-sources", admin, "importable", true));
        var employeeOnly = Set.of("/user", "/user/detail", "/user/{id}/person", "/user/profile/{userId}",
                "/user/{username}", "/user/password/reset/{userId}", "/user/roles/add/{userId}");
        for (var source : sources) {
            assertFalse(employeeOnly.contains(source.path("sourcePath").asString()));
        }
        assertDirectorySource(sources, SOURCE, true);
        assertDirectorySource(sources, SOURCE + "/{id}", false);

        var unnamed = data(getJson(SOURCE + "/" + viewerId, admin));
        assertEquals(viewerId, unnamed.path("id").asLong());
        assertTrue(unnamed.path("nameCn").isNull());
        assertTrue(unnamed.path("nameEn").isNull());
        assertTrue(unnamed.path("departmentId").isNull());
        assertTrue(unnamed.path("departmentName").isNull());
        assertEquals(VIEWER, data(getJson("/user", admin, "username", VIEWER)).path("pageData").get(0)
                .path("username").asString());
    }

    private void assertDirectoryAccessBoundaries(String token) throws Exception {
        getJson(PAGE, token, "username", "directory_private_username").andExpect(status().isBadRequest());
        getJson(PAGE, token, "pageSize", 101).andExpect(status().isBadRequest());
        getJson(PAGE, token, "pageNo", 1_000_001).andExpect(status().isBadRequest());
        getJson(DETAIL, token, "id", 0).andExpect(status().isBadRequest());
        getJson(DETAIL, token, "id", Long.MAX_VALUE).andExpect(status().isNotFound());
        getJson(SOURCE, token).andExpect(status().isUnauthorized());
        getJson("/user", token).andExpect(status().isUnauthorized());
        getJson(ADMIN_API + "/endpoints", token).andExpect(status().isUnauthorized());
    }

    private static void assertDirectorySource(JsonNode sources, String path, boolean page) {
        for (var source : sources) {
            if (path.equals(source.path("sourcePath").asString())) {
                assertTrue(source.path("wholeResponse").asBoolean());
                assertEquals("NONE", source.path("dataScopeType").asString());
                assertEquals(page, source.path("page").asBoolean());
                return;
            }
        }
        throw new AssertionError("未发现用户目录来源 " + path);
    }

    private long user(String username, String nameCn, String nameEn, boolean enabled) {
        fixture.sql("""
                        INSERT INTO `user` (username, nameCn, nameEn, password, mobile, enable)
                        SELECT :username, :nameCn, :nameEn, password, '13800000000', :enabled FROM `user` WHERE id = 1
                        """).param("username", username).param("nameCn", nameCn).param("nameEn", nameEn)
                .param("enabled", enabled).update();
        return fixture.sql("SELECT id FROM `user` WHERE username = :username").param("username", username)
                .query(Long.class).single();
    }

    private long department() {
        String name = "查询目录部门";
        fixture.sql("INSERT INTO sys_department (name) VALUES (?)").param(name).update();
        return fixture.sql("SELECT id FROM sys_department WHERE name = ?").param(name).query(Long.class).single();
    }

    private long publish(String admin, String source, String path, String code) throws Exception {
        String endpoints = ADMIN_API + "/endpoints";
        var imported = data(postJson(endpoints + "/import", admin, map("sourcePath", source, "displayName", code,
                "endpointCode", code, "pathTemplate", path)));
        long id = imported.path("id").asLong();
        configure(admin, endpoints + "/" + id + "/status", map("status", "PUBLISHED", "version", 0));
        return id;
    }

    private void configure(String admin, String path, Object input) throws Exception {
        data(mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(input))));
    }

    private String exchange(String clientId, String secret) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.US_ASCII));
        return body(mockMvc.perform(post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("grant_type", "client_credentials")))
                .path("access_token").asString();
    }
}
