package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

class DepartmentAuditApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void tracksMemberMovesAndAllDepartmentsLedByTheSameAccount() throws Exception {
        String token = loginAsAdmin();
        long parent = create(token, null, "组织测试总部");
        long child = create(token, parent, "组织测试分部");
        long user = createUser(token, "org_leader");
        long member = createUser(token, "org_member");
        String avatar = "/uploads/org-leader.png";
        jdbc.update("UPDATE profile SET avatar=? WHERE userId=?", avatar, user);
        jdbc.update("DELETE FROM profile WHERE userId=?", member);

        var unassigned = data(getJson("/department/members", token, "unassigned", true, "keyword", "org_"));
        assertEquals(2, unassigned.path("total").asInt());
        assertEquals(avatar, unassigned.path("pageData").get(0).path("avatar").asString());
        assertEquals(member, unassigned.path("pageData").get(1).path("id").asLong());
        data(putJson("/department/" + parent + "/leader", token, map("userId", user)));
        data(putJson("/department/" + child + "/leader", token, map("userId", user)));
        data(putJson("/department/members", token, map("userIds", List.of(user, member), "departmentId", child)));
        var move = latest("system.department.members.update");
        assertEquals("SUCCESS", move.path("result").asString());
        assertEquals(2, move.path("objects").size());
        assertEquals(child, move.path("objects").get(0).path("afterData").path("departmentId").path("value").asLong());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_department WHERE leader_user_id=?", Integer.class, user));
        assertEquals(0, data(getJson("/department/members", token, "departmentId", parent)).path("total").asInt());
        assertEquals(2, data(getJson("/department/members", token, "departmentId", parent, "includeDescendants", true))
                .path("total").asInt());
        var userPage = data(getJson("/user", token, "departmentId", child));
        assertEquals(2, userPage.path("total").asInt());
        assertEquals("组织测试分部", userPage.path("pageData").get(0).path("departmentName").asString());
        data(putJson("/department/members", token, map("userIds", List.of(user, member), "departmentId", child)));
        assertEquals("NO_CHANGE", latest("system.department.members.update").path("result").asString());

        data(patchJson("/user/" + user, token, map("enable", false)));
        var disable = latest("system.user.update");
        assertEquals(3, disable.path("objects").size());
        assertEquals(user, disable.path("objects").get(1).path("beforeData").path("leaderUserId").path("value").asLong());
        assertTrue(disable.path("objects").get(2).path("afterData").path("leaderUserId").path("value").isNull());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_department WHERE leader_user_id=?", Integer.class, user));

        data(putJson("/department/" + child, token, department(null, "组织测试分部")));
        var moved = latest("system.department.update").path("objects").get(0);
        assertEquals("组织测试总部 / 组织测试分部", moved.path("beforeData").path("path").path("value").asString());
        assertEquals("组织测试分部", moved.path("afterData").path("path").path("value").asString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsCyclesAndPartialMovesWithoutRecordingCommittedChanges() throws Exception {
        String token = loginAsAdmin();
        long parent = create(token, null, "回滚测试总部");
        Long child = null;
        try {
            child = create(token, parent, "回滚测试分部");
            body(putJson("/department/" + parent, token, department(child, "回滚测试总部")), 400);
            var cycle = latest("system.department.update");
            assertEquals("FAILURE", cycle.path("result").asString());
            assertEquals("REFERENCE", cycle.path("objects").get(0).path("entryType").asString());
            assertNull(jdbc.queryForObject("SELECT parent_id FROM sys_department WHERE id=?", Long.class, parent));

            var current = jdbc.queryForObject("SELECT departmentId FROM `user` WHERE id=1", Long.class);
            body(putJson("/department/members", token,
                    map("userIds", List.of(1, 99999999), "departmentId", child)), 400);
            assertEquals("FAILURE", latest("system.department.members.update").path("result").asString());
            assertEquals(current, jdbc.queryForObject("SELECT departmentId FROM `user` WHERE id=1", Long.class));
        } finally {
            if (child != null) {
                jdbc.update("DELETE FROM sys_department WHERE id=?", child);
            }
            jdbc.update("DELETE FROM sys_department WHERE id=?", parent);
        }
    }

    @Test
    void supportsMultipleMembershipsAndAuditsTransfersAndDeletion() throws Exception {
        String token = loginAsAdmin();
        long parent = create(token, null, "多部门总部");
        long child = create(token, parent, "多部门分部");
        long destination = create(token, null, "多部门调动目标");
        long user = createUser(token, "org_multi_user");
        String path = "/department/users/" + user + "/memberships";

        data(change(token, "ADD", null, parent, user, 0));
        data(change(token, "ADD", null, child, user, 1));
        var membership = data(getJson(path, token));
        assertEquals(parent, membership.path("primaryDepartmentId").asLong());
        assertEquals(2, membership.path("departments").size());
        var matches = data(getJson("/department/members", token, "departmentId", parent, "includeDescendants", true));
        assertEquals(1, matches.path("total").asInt());
        assertEquals(2, matches.path("pageData").get(0).path("matchedDepartments").size());
        assertEquals(1, data(getJson("/department/members", token, "departmentId", child)).path("total").asInt());
        assertEquals(0, data(getJson("/user", token, "departmentId", child)).path("total").asInt());
        assertEquals(1, data(getJson("/user", token, "departmentId", child, "departmentScope", "ANY")).path("total").asInt());

        data(putJson(path, token, map("departmentIds", List.of(parent, child),
                "primaryDepartmentId", child, "expectedVersion", 2)));
        data(change(token, "TRANSFER", child, destination, user, 3));
        membership = data(getJson(path, token));
        assertEquals(destination, membership.path("primaryDepartmentId").asLong());
        assertEquals(2, membership.path("departments").size());
        assertEquals(parent, membership.path("departments").get(0).path("id").asLong());
        var transfer = latest("system.department.members.transfer");
        var changedUser = transfer.path("objects").get(0);
        assertEquals(child, changedUser.path("beforeData").path("departmentId").path("value").asLong());
        assertEquals(destination, changedUser.path("afterData").path("departmentId").path("value").asLong());
        assertEquals(2, changedUser.path("afterData").path("departments").path("value").size());

        data(putJson("/department/" + parent + "/leader", token, map("userId", user)));
        data(putJson("/department/" + destination + "/leader", token, map("userId", user)));
        data(patchJson("/user/" + user, token, map("enable", false)));
        assertEquals(2, data(getJson(path, token)).path("departments").size());
        data(deleteJson("/user/" + user, token));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_department_member WHERE user_id=?", Integer.class, user));
        assertEquals(2, latest("system.user.delete").path("objects").get(0)
                .path("beforeData").path("departments").path("value").size());
    }

    @Test
    void departmentDirectoryAndMemberSelectionEnforceHierarchyAndEligibility() throws Exception {
        String token = loginAsAdmin();
        long root = create(token, null, "Coverage directory");
        long child = create(token, root, "Coverage subdirectory");
        long person = createUser(token, "org_coverage_person");
        assertTrue(data(getJson("/department", token)).valueStream().anyMatch(item -> item.path("id").asLong() == child));
        var candidates = data(getJson("/department/users", token, "keyword", "org_coverage_person", "enable", true));
        assertEquals(1, candidates.path("total").asLong());
        body(getJson("/department/members", token, "departmentId", root, "unassigned", true), 400);
        body(postJson("/department", token, department(root, "coverage subdirectory")), 400);
        body(postJson("/department", token, department(9999999L, "Missing parent")), 400);
        body(putJson("/department/users/" + person + "/memberships", token, map("departmentIds", List.of(root),
                "primaryDepartmentId", child, "expectedVersion", 0)), 400);
        body(change(token, "TRANSFER", root, child, person, 0), 400);
        body(change(token, "TRANSFER", root, root, person, 0), 400);
        var repeatedUser = map("userId", person, "expectedVersion", 0);
        body(postJson("/department/member-changes", token, map("action", "ADD", "targetDepartmentId", root,
                "users", List.of(repeatedUser, repeatedUser))), 400);

        data(putJson("/department/" + child + "/leader", token, map("userId", person)));
        data(putJson("/department/" + child, token, map("parentId", root, "name", "Coverage subdirectory",
                "sortOrder", 0, "enabled", false)));
        assertNull(jdbc.queryForObject("SELECT leader_user_id FROM sys_department WHERE id = ?", Long.class, child));
        body(postJson("/department", token, department(child, "Disabled parent")), 400);
        data(patchJson("/user/" + person, token, map("enable", false)));
        body(putJson("/department/" + root + "/leader", token, map("userId", person)), 400);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsStaleAndUnsafeBatchesWithoutPartialWrites() throws Exception {
        String token = loginAsAdmin();
        long source = create(token, null, "批量主部门");
        long secondary = create(token, null, "批量兼岗部门");
        long first = createUser(token, "org_batch_first");
        long second = createUser(token, "org_batch_second");
        try {
            data(change(token, "ADD", null, source, first, 0));
            data(change(token, "ADD", null, source, second, 0));
            data(change(token, "ADD", null, secondary, second, 1));

            body(postJson("/department/member-changes", token, map("action", "REMOVE", "sourceDepartmentId", source,
                    "users", List.of(map("userId", first, "expectedVersion", 1),
                            map("userId", second, "expectedVersion", 2)))), 400);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_department_member WHERE department_id=?", Integer.class, source));
            assertEquals("FAILURE", latest("system.department.members.remove").path("result").asString());

            body(change(token, "ADD", null, secondary, first, 0), 409);
            body(putJson("/department/members", token, map("userIds", List.of(first, second), "departmentId", null)), 409);
            body(putJson("/department/" + secondary, token,
                    map("parentId", null, "name", "批量兼岗部门", "sortOrder", 0, "enabled", false)), 400);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_department_member WHERE department_id=?", Integer.class, secondary));

            data(change(token, "REMOVE", source, null, first, 1));
            assertNull(jdbc.queryForObject("SELECT departmentId FROM `user` WHERE id=?", Long.class, first));
        } finally {
            data(deleteJson("/user/" + first, token));
            data(deleteJson("/user/" + second, token));
            jdbc.update("DELETE FROM sys_department WHERE id IN (?, ?)", source, secondary);
        }
    }

    @Test
    void userFormsSaveMembershipsAndPersonDetailsFollowDirectoryPermissions() throws Exception {
        String admin = loginAsAdmin();
        long primary = create(admin, null, "用户表单主部门");
        long secondary = create(admin, null, "用户表单兼岗部门");
        String username = "person_form_user";
        var created = data(postJson("/user", admin, map("username", username, "nameCn", "测试人员",
                "nameEn", "Test Person", "enable", true, "roleIds", List.of(2),
                "profile", map("email", "person@example.test"),
                "memberships", map("departmentIds", List.of(primary, secondary), "primaryDepartmentId", primary))));
        long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        String detailsPath = "/user/" + userId + "/person";
        var person = data(getJson(detailsPath, admin));
        assertEquals("测试人员", person.path("nameCn").asString());
        assertEquals("Test Person", person.path("nameEn").asString());
        assertEquals("person@example.test", person.path("email").asString());
        assertEquals(primary, person.path("primaryDepartmentId").asLong());
        assertEquals(2, person.path("departments").size());
        assertEquals(1, person.path("roles").size());
        assertFalse(person.has("password"));
        assertFalse(person.has("domainAccount"));
        assertEquals(2, latest("system.user.create").path("objects").get(0)
                .path("afterData").path("departments").path("value").size());

        data(patchJson("/user/" + userId, admin, map("nameCn", "编辑后人员",
                "memberships", map("departmentIds", List.of(secondary), "primaryDepartmentId", secondary, "expectedVersion", 1))));
        person = data(getJson(detailsPath, admin));
        assertEquals("编辑后人员", person.path("nameCn").asString());
        assertEquals(secondary, person.path("primaryDepartmentId").asLong());
        var edit = latest("system.user.update").path("objects").get(0);
        assertEquals(2, edit.path("beforeData").path("departments").path("value").size());
        assertEquals(1, edit.path("afterData").path("departments").path("value").size());

        String temporary = created.path("temporaryPassword").asString();
        String restricted = login(username, temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", "PersonForm123!")));
        String ordinary = login(username, "PersonForm123!");
        assertEquals(userId, data(getJson(detailsPath, ordinary)).path("id").asLong());
        code(getJson("/user/1/person", ordinary), 11003);

        body(patchJson("/user/" + userId, admin, map("nameCn", "并发覆盖",
                "memberships", map("departmentIds", List.of(primary), "primaryDepartmentId", primary, "expectedVersion", 1))), 409);
        assertEquals("编辑后人员", data(getJson(detailsPath, admin)).path("nameCn").asString());
    }

    private ResultActions change(String token, String action, Long source, Long target, long user, long version) throws Exception {
        return postJson("/department/member-changes", token, map("action", action, "sourceDepartmentId", source,
                "targetDepartmentId", target, "users", List.of(map("userId", user, "expectedVersion", version))));
    }

    private long createUser(String token, String username) throws Exception {
        data(postJson("/user", token, map("username", username, "enable", true, "roleIds", List.of(2))));
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
    }

    private long create(String token, Long parent, String name) throws Exception {
        return data(postJson("/department", token, department(parent, name))).asLong();
    }

    private Object department(Long parent, String name) {
        return map("parentId", parent, "name", name, "sortOrder", 0, "enabled", true);
    }

    private ResultActions putJson(String url, String token, Object body) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode latest(String code) {
        return objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1", String.class, code));
    }
}
