package me.lendev.flow.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class LocalizedErrorResponseTest extends ApiTestBase {

    @Test
    void localizesLoginErrorsAndDefaultsUnsupportedLanguagesToChinese() throws Exception {
        String login = """
                {"username":"admin","password":"123456","captchaKey":"unknown-key","captcha":"1234"}
                """;
        mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en-US"))
                .andExpect(header().stringValues(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("Incorrect verification code"));

        mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR")
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "zh-CN"))
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("验证码错误"));
    }

    @Test
    void beanValidationUsesTheRequestLanguageAndKeepsTheBusinessCode() throws Exception {
        String missingUsername = """
                {"password":"123456"}
                """;
        mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR,en-GB;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON).content(missingUsername))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Username is required"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(missingUsername))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "zh-CN"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("username不能为空"));
    }

    @Test
    void interpolatesBeanValidationLimitsFromTheConstraint() throws Exception {
        String token = loginAsAdmin();
        String group = writeJson(map("code", "long-name", "name", "a".repeat(101)));
        mockMvc.perform(post("/param/groups").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                        .contentType(MediaType.APPLICATION_JSON).content(group))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Name must be at most 100 characters"));
    }

    @Test
    void localizesDynamicFieldErrorsAndKeepsUserDefinedLabelsVerbatim() throws Exception {
        String token = loginAsAdmin();
        String label = "地区 {0}";
        long groupId = data(postJson("/param/groups", token, map(
                "code", "localized-fields",
                "name", "地区配置",
                "fieldConfig", map("fixedFields", map("value1", map("label", label, "required", true))))))
                .get("id").asLong();
        String path = "/param/groups/" + groupId + "/items";
        String item = """
                {"code":"sample","name":"测试明细"}
                """;

        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                        .contentType(MediaType.APPLICATION_JSON).content(item))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("“地区 {0}” is required"));

        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON).content(item))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("地区 {0}不能为空"));
    }

    @Test
    void localizesAuthenticationAndMalformedRequestFailures() throws Exception {
        mockMvc.perform(get("/user/detail").header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Sign in again to continue. Your session may have expired."));

        mockMvc.perform(post("/auth/login").header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("The request body is missing or has an invalid format."));
    }

    @Test
    void bothCatalogsCoverTheSameKeys() throws IOException {
        Properties chinese = loadCatalog("");
        Properties english = loadCatalog("_" + Locale.ENGLISH.getLanguage());
        assertEquals(chinese.stringPropertyNames(), english.stringPropertyNames());
    }

    private Properties loadCatalog(String suffix) throws IOException {
        var properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/i18n/messages" + suffix + ".properties");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
