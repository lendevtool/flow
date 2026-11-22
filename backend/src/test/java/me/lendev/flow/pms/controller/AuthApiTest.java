package me.lendev.flow.pms.controller;

import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/**
 * 鉴权接口测试
 */
class AuthApiTest extends ApiTestBase {

    @Autowired
    private UserService userService;

    @Test
    void loginAndDetail() throws Exception {
        String token = loginAsAdmin();
        assertNotNull(token);

        JsonNode detail = data(getJson("/user/detail", token));
        assertEquals(ADMIN, detail.get("username").asString());
        assertTrue(detail.get("enable").asBoolean());
        assertEquals("SUPER_ADMIN", detail.get("currentRole").get("code").asString());
        assertEquals("Admin", detail.get("profile").get("nickName").asString());
        assertEquals(2, detail.get("roles").size());
    }

    @Test
    void loginWithSessionCaptchaKey() throws Exception {
        Pair<String, ICaptcha> captcha = captchaService.create();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("captchaKey", captcha.getKey());

        JsonNode data = data(mockMvc.perform(post("/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map(
                        "username", ADMIN,
                        "password", ADMIN_PASSWORD,
                        "captcha", captcha.getValue().getCode())))));
        assertNotNull(data.get("accessToken").asString());
    }

    @Test
    void captchaEndpointWritesImage() throws Exception {
        byte[] image = mockMvc.perform(get("/auth/captcha"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(image.length > 0);
    }

    @Test
    void loginWithWrongCaptcha() throws Exception {
        Pair<String, ICaptcha> captcha = captchaService.create();
        code(postJson("/auth/login", map(
                "username", ADMIN,
                "password", ADMIN_PASSWORD,
                "captchaKey", captcha.getKey(),
                "captcha", "ZZZZ")), 10003);
    }

    @Test
    void loginWithUnknownCaptchaKey() throws Exception {
        code(postJson("/auth/login", map(
                "username", ADMIN,
                "password", ADMIN_PASSWORD,
                "captchaKey", "unknown-key",
                "captcha", "1234")), 10003);
    }

    @Test
    void loginWithoutCaptcha() throws Exception {
        code(postJson("/auth/login", map(
                "username", ADMIN,
                "password", ADMIN_PASSWORD)), 10003);
    }

    @Test
    void loginWithUnknownUser() throws Exception {
        var captcha = captchaService.create();
        code(postJson("/auth/login", map(
                "username", "nobody",
                "password", ADMIN_PASSWORD,
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())), 10002);
    }

    @Test
    void loginWithWrongPassword() throws Exception {
        Pair<String, ICaptcha> captcha = captchaService.create();
        code(postJson("/auth/login", map(
                "username", ADMIN,
                "password", "wrong-password",
                "captchaKey", captcha.getKey(),
                "captcha", captcha.getValue().getCode())), 10002);
    }

    @Test
    void refreshToken() throws Exception {
        String token = loginAsAdmin();
        JsonNode data = data(getJson("/auth/refresh/token", token));
        assertNotNull(data.get("accessToken").asString());
    }

    @Test
    void switchRole() throws Exception {
        String token = loginAsAdmin();
        String roleToken = data(postJson("/auth/current-role/switch/ROLE_QA", token, Map.of()))
                .get("accessToken").asString();
        assertEquals("ROLE_QA", data(getJson("/user/detail", roleToken))
                .get("currentRole").get("code").asString());
    }

    @Test
    void switchToUnknownRole() throws Exception {
        String token = loginAsAdmin();
        code(postJson("/auth/current-role/switch/NOT_EXISTS", token, Map.of()), 11005);
    }

    @Test
    void logout() throws Exception {
        String token = loginAsAdmin();
        code(postJson("/auth/logout", token, Map.of()), 0);
    }

    @Test
    void register() throws Exception {
        data(postJson("/auth/register", loginAsAdmin(), map(
                "username", "registered",
                "password", "pw123456",
                "profile", Map.of("nickName", "reg"))));
        User saved = userService.queryChain().and(USER.USERNAME.eq("registered")).one();
        assertNotNull(saved.getId());
    }

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        JsonNode body = body(mockMvc.perform(get("/user/detail")), 401);
        assertEquals(401, body.get("code").asInt());
    }

    @Test
    void requestWithInsufficientRoleIsRejected() throws Exception {
        String roleToken = data(postJson("/auth/current-role/switch/ROLE_QA", loginAsAdmin(), Map.of()))
                .get("accessToken").asString();
        code(postJson("/role", roleToken, map("code", "X", "name", "X")), 11003);
    }

    @Test
    void notImplementedEndpoints() throws Exception {
        String token = loginAsAdmin();
        assertEquals("接口未实现", code(getJson("/user/admin", token), 11006).get("message").asString());
        assertEquals("接口未实现", code(getJson("/user/profile/1", token), 11006).get("message").asString());
    }

    @Test
    void captchaResponseIncludesKeyAndPreventsCaching() throws Exception {
        var response = mockMvc.perform(get("/auth/captcha")).andReturn().getResponse();
        assertEquals("image/png", response.getContentType());
        assertNotNull(response.getHeader("X-Captcha-Key"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Set-Cookie").contains("flow-captcha="));
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
    }

    @Test
    void explicitCaptchaKeyTakesPrecedenceOverStaleSession() throws Exception {
        var captcha = captchaService.create();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("captchaKey", "expired-challenge");
        data(mockMvc.perform(post("/auth/login").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("username", ADMIN, "password", ADMIN_PASSWORD,
                        "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())))));
    }

    @Test
    void loginAcceptsCaptchaCookieWithoutServletSession() throws Exception {
        var captcha = captchaService.create();
        var result = mockMvc.perform(post("/auth/login")
                .cookie(new jakarta.servlet.http.Cookie("flow-captcha", captcha.getKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("username", ADMIN, "password", ADMIN_PASSWORD,
                        "captcha", captcha.getValue().getCode()))));
        data(result);
        org.junit.jupiter.api.Assertions.assertNull(result.andReturn().getRequest().getSession(false));
    }

    @Test
    void captchaCookieReachesLoginBehindTheFrontendApiProxy() throws Exception {
        var response = mockMvc.perform(get("/auth/captcha")).andReturn().getResponse();
        var cookie = java.net.HttpCookie.parse(response.getHeader("Set-Cookie")).getFirst();
        // 浏览器实际访问的是 /api/auth/login，Vite 代理转发前会去掉 /api 前缀，
        // 因此 Cookie 的 Path 必须是该路径的前缀，登录后浏览器才会回传验证码 Cookie。
        assertTrue("/api/auth/login".startsWith(cookie.getPath()));
    }

}
