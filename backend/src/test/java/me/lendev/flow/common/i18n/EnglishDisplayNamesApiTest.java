package me.lendev.flow.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;

class EnglishDisplayNamesApiTest extends ApiTestBase {

    @Test
    void exposesResourceNamesInTheTreeAndSupportsPartialUpdatesAndClearing() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/permission", token, map("name", "销售看板", "nameEn", " Sales board ",
                "code", "DisplayNamesMenu", "type", "MENU")));

        var menus = data(getJson("/role/permissions/tree", token));
        var menu = menus.values().stream().filter(row -> row.path("code").asString().equals("DisplayNamesMenu"))
                .findFirst().orElseThrow();
        assertEquals("Sales board", menu.path("nameEn").asString());

        String path = "/permission/" + menu.path("id").asLong();
        data(patchJson(path, token, map("name", "新销售看板")));
        assertEquals("Sales board", data(getJson(path, token)).path("nameEn").asString());
        data(patchJson(path, token, map("nameEn", "Revenue board")));
        assertEquals("Revenue board", data(getJson(path, token)).path("nameEn").asString());
        data(patchJson(path, token, map("nameEn", null)));
        var updated = data(getJson(path, token));
        assertEquals("新销售看板", updated.path("name").asString());
        assertTrue(updated.path("nameEn").isNull());
        code(patchJson(path, token, map("nameEn", "a".repeat(101))), 400);
    }

    @Test
    void searchesRoleNamesInEitherLanguageAndClearsTheOptionalName() throws Exception {
        String token = loginAsAdmin();
        data(postJson("/role", token, map("code", "DISPLAY_NAMES_ROLE", "name", "项目审核员",
                "nameEn", "Project reviewer", "enable", true)));
        var page = data(getJson("/role/page", token, "name", "Project reviewer"));
        assertEquals(1, page.path("total").asInt());
        var role = page.path("pageData").get(0);
        String path = "/role/" + role.path("id").asLong();
        data(patchJson(path, token, map("enable", true)));
        assertEquals("Project reviewer", data(getJson(path, token)).path("nameEn").asString());
        data(patchJson(path, token, map("nameEn", "  ")));
        assertTrue(data(getJson(path, token)).path("nameEn").isNull());
        assertEquals(1, data(getJson("/role/page", token, "name", "项目审核员")).path("total").asInt());
    }

    @Test
    void usesASeparateUserDisplayNameWhileKeepingTheLoginAccount() throws Exception {
        String admin = loginAsAdmin();
        String account = "display_names_user";
        var created = data(postJson("/user", admin, map("username", account, "enable", true,
                "nameEn", "Jane Smith", "profile", map("nickName", "小张"), "roleIds", List.of(2))));
        var user = data(getJson("/user", admin, "username", account)).path("pageData").get(0);
        assertEquals("小张", user.path("nickName").asString());
        assertEquals("Jane Smith", user.path("nameEn").asString());

        String temporary = created.path("temporaryPassword").asString();
        String restricted = login(account, temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", "Display1234!")));
        String token = login(account, "Display1234!");
        String path = "/user/" + user.path("id").asLong();
        data(patchJson(path, admin, map("nameEn", " Jane Doe ")));
        var detail = data(getJson("/user/detail", token));
        assertEquals(account, detail.path("username").asString());
        assertEquals("Jane Doe", detail.path("nameEn").asString());
        data(patchJson(path, admin, map("nameEn", null)));
        assertTrue(data(getJson("/user/detail", token)).path("nameEn").isNull());
    }

    @Test
    void exposesParameterNamesInSearchAndOptionsAndPreservesOmittedValues() throws Exception {
        String token = loginAsAdmin();
        var group = data(postJson("/param/groups", token,
                map("code", "display_names", "name", "配送状态", "nameEn", "Delivery status")));
        long groupId = group.path("id").asLong();
        String groupPath = "/param/groups/" + groupId;
        String itemPath = groupPath + "/items";
        var item = data(postJson(itemPath, token, map("code", "pending", "name", "待处理", "nameEn", "Pending")));
        assertEquals("Delivery status", group.path("nameEn").asString());
        assertEquals("Pending", item.path("nameEn").asString());
        assertEquals(1, data(getJson("/param/groups", token, "keyword", "Delivery")).path("total").asInt());
        assertEquals(1, data(getJson(itemPath, token, "keyword", "Pending")).path("total").asInt());

        var update = map("code", "display_names", "name", "配送状态");
        assertEquals("Delivery status", data(patchJson(groupPath, token, update)).path("nameEn").asString());
        update.put("nameEn", null);
        assertTrue(data(patchJson(groupPath, token, update)).path("nameEn").isNull());

        String itemUpdate = itemPath + "/" + item.path("id").asLong();
        var options = "/param/options/display_names";
        assertEquals("Pending", data(getJson(options, token)).get(0).path("nameEn").asString());
        assertTrue(data(patchJson(itemUpdate, token, map("code", "pending", "name", "待处理", "nameEn", " ")))
                .path("nameEn").isNull());
        assertTrue(data(getJson(options, token)).get(0).path("nameEn").isNull());
    }
}
