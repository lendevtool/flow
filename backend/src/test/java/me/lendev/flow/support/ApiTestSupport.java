package me.lendev.flow.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import me.lendev.flow.pms.service.CaptchaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 接口测试的公共辅助方法，由具体环境的基类继承
 */
public abstract class ApiTestSupport {

    protected static final String ADMIN = "admin";

    protected static final String ADMIN_PASSWORD = "123456";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected CaptchaService captchaService;

    /**
     * 用图形验证码登录，返回 accessToken
     */
    protected String login(String username, String password) throws Exception {
        Pair<String, ICaptcha> captcha = captchaService.create();
        Map<String, Object> body = map(
                "username", username,
                "password", password,
                "captchaKey", captcha.getKey(),
                "captcha", captcha.getValue().getCode());
        return data(postJson("/auth/login", body)).get("accessToken").asString();
    }

    protected String loginAsAdmin() throws Exception {
        return login(ADMIN, ADMIN_PASSWORD);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected ResultActions postJson(String url, Object body) throws Exception {
        return perform(post(url), null, body);
    }

    protected ResultActions postJson(String url, String token, Object body) throws Exception {
        return perform(post(url), token, body);
    }

    protected ResultActions patchJson(String url, String token, Object body) throws Exception {
        return perform(patch(url), token, body);
    }

    protected ResultActions getJson(String url, String token) throws Exception {
        return perform(get(url), token, null);
    }

    protected ResultActions getJson(String url, String token, Object... query) throws Exception {
        MockHttpServletRequestBuilder builder = get(url);
        for (int i = 0; i < query.length; i += 2) {
            builder = builder.queryParam((String) query[i], String.valueOf(query[i + 1]));
        }
        return perform(builder, token, null);
    }

    protected ResultActions deleteJson(String url, String token) throws Exception {
        return perform(delete(url), token, null);
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String token, Object body)
            throws Exception {
        if (token != null) {
            builder = builder.header("Authorization", bearer(token));
        }
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder);
    }

    /**
     * 断言业务成功并返回 data 节点
     */
    protected JsonNode data(ResultActions actions) throws Exception {
        JsonNode node = body(actions);
        assertEquals(0, node.get("code").asInt(), node.toString());
        return node.get("data");
    }

    /**
     * 断言业务响应码
     */
    protected JsonNode code(ResultActions actions, int expected) throws Exception {
        JsonNode node = body(actions);
        assertEquals(expected, node.get("code").asInt(), node.toString());
        return node;
    }

    protected JsonNode body(ResultActions actions) throws Exception {
        return body(actions, 200);
    }

    /**
     * 断言 http 状态码并解析响应体
     */
    protected JsonNode body(ResultActions actions, int httpStatus) throws Exception {
        String content = actions.andExpect(status().is(httpStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(content);
    }

    protected Map<String, Object> map(Object... keyValues) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return body;
    }

    protected String writeJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

}
