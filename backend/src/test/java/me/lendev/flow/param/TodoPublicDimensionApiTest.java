package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TodoPublicDimensionApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ordinaryParameterEditorCannotChangeSlotOrDedicatedDictionary() throws Exception {
        String token = loginAsAdmin();
        long groupId = groupId("WORKBENCH_TODO_DIMENSION");
        long slotId = slotId(groupId, "DIMENSION_1");
        String dictionary = "WORKBENCH_TODO_DICT_TEST";
        long dictionaryId = createGroup(token, dictionary);
        for (String permission : List.of("ParamMgt", "ParamCreate", "ParamEdit", "ParamDelete", "ParamSort")) {
            jdbc.update("""
                    INSERT INTO role_permissions_permission (roleId, permissionId)
                    SELECT 2, id FROM permission WHERE code = ?
                    """, permission);
        }
        data(postJson("/auth/current-role/switch/ROLE_QA", token, map()));

        code(patchJson("/param/groups/" + groupId + "/items/" + slotId, token,
                map("code", "DIMENSION_1", "name", "Denied", "enable", false)), 11003);
        code(postJson("/param/groups/" + dictionaryId + "/items", token,
                map("code", "DENIED", "name", "Denied")), 11003);
        code(postJson("/param/groups", token,
                map("code", "WORKBENCH_TODO_DICT_OTHER", "name", "Denied")), 11003);
        createGroup(token, "ORDINARY_PARAMETERS");
    }

    @Test
    void slotsRequireDedicatedDistinctDictionariesAndIgnoreClientLockFlags() throws Exception {
        String token = loginAsAdmin();
        long groupId = groupId("WORKBENCH_TODO_DIMENSION");
        long first = slotId(groupId, "DIMENSION_1");
        long second = slotId(groupId, "DIMENSION_2");
        long dictionaryId = createGroup(token, "WORKBENCH_TODO_DICT_PROJECTS");
        long replacementId = createGroup(token, "WORKBENCH_TODO_DICT_REGIONS");
        long ordinary = createGroup(token, "ORDINARY_DICTIONARY");
        String path = "/param/groups/" + groupId + "/items/";

        body(patchJson(path + first, token,
                map("code", "DIMENSION_1", "name", "Ordinary", "value1", Long.toString(ordinary))), 400);
        var slot = data(patchJson(path + first, token, map("code", "DIMENSION_1", "name", "Project",
                "value1", Long.toString(dictionaryId), "extra", map("bindingLocked", true))));
        assertFalse(slot.path("extra").path("bindingLocked").asBoolean(false));
        body(patchJson(path + second, token, map("code", "DIMENSION_2", "name", "Duplicate",
                "value1", Long.toString(dictionaryId))), 400);
        body(deleteJson("/param/groups/" + dictionaryId, token), 400);
        body(deleteJson(path + first, token), 400);
        body(patchJson("/param/groups/" + groupId, token,
                map("code", "WORKBENCH_TODO_DIMENSION", "name", "Disabled", "enable", false)), 400);
        var replaced = data(patchJson(path + first, token,
                map("code", "DIMENSION_1", "name", "Region", "value1", Long.toString(replacementId))));
        assertEquals(Long.toString(replacementId), replaced.path("value1").asString());
    }

    private long createGroup(String token, String code) throws Exception {
        return data(postJson("/param/groups", token, map("code", code, "name", code))).path("id").asLong();
    }

    private long groupId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = ?", Long.class, code);
    }

    private long slotId(long groupId, String code) {
        return jdbc.queryForObject("SELECT id FROM sys_param_item WHERE groupId = ? AND code = ?",
                Long.class, groupId, code);
    }
}
