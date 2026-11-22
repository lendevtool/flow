package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ParamGroupSortTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private long create(String token, String code, boolean enable) throws Exception {
        return data(postJson("/param/groups", token, map("code", code, "name", code, "enable", enable))).get("id").asLong();
    }

    @Test
    void sortingIncludesAllPagesAndDisabledGroupsAndPreservesChildrenAndCreator() throws Exception {
        String token = loginAsAdmin();
        var existing = order(token);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(create(token, "group_" + i, i != 3));
        }
        long first = ids.getFirst();
        long itemId = data(postJson("/param/groups/" + first + "/items", token,
                map("code", "entry", "name", "明细", "sort", 9, "value1", "保留数据"))).get("id").asLong();
        long version = data(getJson("/param/groups/" + first, token)).get("version").asLong();
        jdbc.update("UPDATE sys_param_group SET createdBy = 98765, updatedBy = NULL WHERE id = ?", first);

        var all = data(getJson("/param/groups/order", token));
        assertEquals(existing.size() + 12, all.size());
        assertFalse(all.get(existing.size() + 3).get("enable").asBoolean());
        assertEquals(first, all.get(existing.size()).get("id").asLong());
        Collections.reverse(ids);
        ids.addAll(existing);
        data(patchJson("/param/groups/sort", token, map("ids", ids)));

        var sorted = data(getJson("/param/groups/order", token));
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(ids.get(i), sorted.get(i).get("id").asLong());
            assertEquals(i + 1, sorted.get(i).get("sort").asInt());
            assertEquals(1L, sorted.get(i).get("updatedBy").asLong());
        }
        var page = data(getJson("/param/groups", token, "pageNo", 2, "pageSize", 10));
        assertEquals(existing.size() + 12, page.get("total").asInt());
        assertEquals(first, page.get("pageData").get(1).get("id").asLong());
        var group = data(getJson("/param/groups/" + first, token));
        assertEquals(98765L, group.get("createdBy").asLong());
        assertEquals(version, group.get("version").asLong());
        var item = data(getJson("/param/groups/" + first + "/items/" + itemId, token));
        assertEquals(9, item.get("sort").asInt());
        assertEquals("保留数据", item.get("value1").asString());
    }

    @Test
    void duplicateMissingUnknownAndOutdatedListsLeaveExistingOrderUnchanged() throws Exception {
        String token = loginAsAdmin();
        var existing = order(token);
        long a = create(token, "a", true);
        long b = create(token, "b", true);
        List<Long> invalid = new ArrayList<>(existing);
        invalid.add(a);
        invalid.add(a);
        body(patchJson("/param/groups/sort", token, map("ids", invalid)), 400);
        invalid.removeLast();
        body(patchJson("/param/groups/sort", token, map("ids", invalid)), 400);
        invalid.add(9999999L);
        body(patchJson("/param/groups/sort", token, map("ids", invalid)), 400);
        assertEquals(0, data(getJson("/param/groups/" + a, token)).get("sort").asInt());
        var stale = order(token);
        create(token, "added_concurrently", true);
        body(patchJson("/param/groups/sort", token, map("ids", stale)), 400);
        assertEquals(0, data(getJson("/param/groups/" + b, token)).get("sort").asInt());
    }

    @Test
    void classificationOrderRequiresTheExistingSortPermission() throws Exception {
        body(getJson("/param/groups/order", null), 401);
        String token = loginAsAdmin();
        long id = create(token, "permissions", true);
        var fullOrder = order(token);
        jdbc.update("INSERT INTO role_permissions_permission (roleId, permissionId) SELECT 2, id FROM permission WHERE code = 'ParamMgt'");
        data(postJson("/auth/current-role/switch/ROLE_QA", token, map()));
        code(getJson("/param/groups/order", token), 11003);
        code(patchJson("/param/groups/sort", token, map("ids", List.of(id))), 11003);
        jdbc.update("INSERT INTO role_permissions_permission (roleId, permissionId) SELECT 2, id FROM permission WHERE code = 'ParamSort'");
        assertEquals(fullOrder, order(token));
        data(patchJson("/param/groups/sort", token, map("ids", fullOrder)));
    }

    private List<Long> order(String token) throws Exception {
        var result = new ArrayList<Long>();
        data(getJson("/param/groups/order", token)).forEach(group -> result.add(group.path("id").asLong()));
        return result;
    }
}
