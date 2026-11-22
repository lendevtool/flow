package me.lendev.flow.pms.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;

/**
 * 校验个人界面偏好的默认值、账号隔离和部分更新。
 */
class UserAppearanceApiTest extends ApiTestBase {

    private static final String APPEARANCE = "/user/profile/appearance";
    private static final Map<String, Object> DEFAULTS = Map.of(
            "colorMode", "auto", "primaryColor", "#316C72", "language", "zh-CN",
            "layout", "normal", "tableSize", "medium", "layoutSettingVisible", true);

    @Test
    void persistsOnlyChangedFieldsForTheLoggedInAccount() throws Exception {
        String admin = loginAsAdmin();
        var defaults = objectMapper.valueToTree(DEFAULTS);
        assertEquals(defaults, data(getJson("/user/detail", admin)).path("appearance"));

        var saved = map("colorMode", "dark", "primaryColor", "hsla(182, 30%, 45%, 0.9)", "language", "en-US",
                "layout", "empty", "tableSize", "large", "layoutSettingVisible", false);
        data(patchJson(APPEARANCE, admin, saved));
        data(patchJson(APPEARANCE, admin, Map.of("layout", "simple")));
        data(patchJson(APPEARANCE, admin, map("language", null)));
        data(patchJson(APPEARANCE, admin, Map.of()));
        saved.put("layout", "simple");

        var created = data(postJson("/user", admin, map("username", "appearance_user", "roleIds", List.of(2))));
        String password = "App-Preferences234!";
        String temporary = login("appearance_user", created.path("temporaryPassword").asString());
        data(postJson("/auth/password", temporary, map(
                "oldPassword", created.path("temporaryPassword").asString(), "newPassword", password)));
        String other = login("appearance_user", password);
        assertEquals(defaults, data(getJson("/user/detail", other)).path("appearance"));

        data(patchJson(APPEARANCE, other, Map.of("language", "en-US")));
        assertEquals("en-US", data(getJson("/user/detail", other)).path("appearance").path("language").asString());
        assertEquals(objectMapper.valueToTree(saved), data(getJson("/user/detail", loginAsAdmin())).path("appearance"));
    }

    @Test
    void rejectsAnonymousAndInvalidUpdates() throws Exception {
        body(patchJson(APPEARANCE, null, Map.of("language", "en-US")), 401);
        String admin = loginAsAdmin();
        code(patchJson(APPEARANCE, admin, Map.of("language", "fr-FR", "layout", "full")), 400);
        code(patchJson(APPEARANCE, admin, Map.of("primaryColor", "url(https://example.com)")), 400);
        assertEquals(objectMapper.valueToTree(DEFAULTS), data(getJson("/user/detail", admin)).path("appearance"));
    }
}
