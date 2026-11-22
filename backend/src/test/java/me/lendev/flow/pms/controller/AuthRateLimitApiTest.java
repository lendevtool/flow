package me.lendev.flow.pms.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flow_rate_limit;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
class AuthRateLimitApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void useLowQuotaForRateLimitCases() {
        var config = objectMapper.readTree(jdbc.queryForObject(
                "SELECT config FROM sys_system_policy WHERE policy_code = 'AUTH_SECURITY'", String.class));
        var rate = (ObjectNode) config.path("rateLimit");
        rate.put("loginPerIp", 2).put("loginPerAccount", 2).put("captchaPerIp", 2);
        jdbc.update("UPDATE sys_system_policy SET config = ? WHERE policy_code = 'AUTH_SECURITY'",
                objectMapper.writeValueAsString(config));
    }

    @Test
    void captchaLimitIgnoresSpoofedForwardedAddressesAndReturnsRetryAfter() throws Exception {
        String remoteAddress = "captcha-test-" + UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(get("/auth/captcha")
                            .with(request -> {
                                request.setRemoteAddr(remoteAddress);
                                return request;
                            })
                            .header("X-Forwarded-For", "192.0.2." + attempt))
                    .andExpect(status().isOk());
        }
        var response = mockMvc.perform(get("/auth/captcha")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .header("X-Forwarded-For", "203.0.113.25"))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        long retryAfter = Long.parseLong(response.getHeader("Retry-After"));
        assertTrue(retryAfter > 0 && retryAfter <= 60);
        assertNull(response.getHeader("X-Captcha-Key"));
        assertEquals(429, objectMapper.readTree(response.getContentAsString()).get("code").asInt());
    }

    @Test
    void loginLimitCountsInvalidCaptchaAttemptsAcrossAddresses() throws Exception {
        String username = "rate-test-" + UUID.randomUUID();
        for (int attempt = 0; attempt < 3; attempt++) {
            String remoteAddress = "login-test-" + UUID.randomUUID();
            mockMvc.perform(post("/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(remoteAddress);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(writeJson(map("username", username, "password", "invalid"))))
                    .andExpect(attempt < 2 ? status().isOk() : status().isTooManyRequests());
        }
    }
}
