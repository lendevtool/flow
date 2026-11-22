package me.lendev.flow.pms.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.lendev.flow.pms.domain.request.UserPageRequest;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersistenceQueryTest extends ApiTestBase {

    @Autowired
    UserService users;
    @Autowired
    RoleMapper roles;
    @Autowired
    PermissionMapper permissions;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void leftJoinRetainsUserWithoutProfile() {
        jdbc.update("insert into `user` (id, username, password, enable) values (9001, 'dsl_orphan', 'hash', true)");
        UserPageRequest request = new UserPageRequest();
        request.setUsername("dsl_orphan");
        var page = users.queryPage(request);
        assertEquals(1L, page.getTotal());
        assertEquals(9001L, page.getPageData().getFirst().getId());
        assertNull(page.getPageData().getFirst().getEmail());
    }

    @Test
    void combinedFiltersAndPagingRetainCountAndProjection() {
        jdbc.update("insert into `user` (id, username, password, enable) values (9002, 'dsl_filter', 'hash', false)");
        jdbc.update("insert into profile (userId, gender, email) values (9002, 1, 'dsl@example.com')");
        UserPageRequest request = new UserPageRequest();
        request.setUsername("dsl_filter");
        request.setGender(1);
        request.setEnable(false);
        request.setPageSize(1);
        var page = users.queryPage(request);
        assertEquals(1L, page.getTotal());
        assertEquals("dsl@example.com", page.getPageData().getFirst().getEmail());
        request.setPageNo(2);
        assertEquals(1L, users.queryPage(request).getTotal());
        assertTrue(users.queryPage(request).getPageData().isEmpty());
        request.setPageNo(1);
        request.setEnable(true);
        assertEquals(0L, users.queryPage(request).getTotal());
        request.setEnable(false);
        request.setGender(2);
        assertEquals(0L, users.queryPage(request).getTotal());
    }

    @Test
    void membershipQueriesExcludeUnrelatedRows() {
        jdbc.update("insert into `user` (id, username, password, enable) values (9001, 'dsl_member', 'hash', true)");
        jdbc.update("insert into role (id, code, name) values (9001, 'dsl_role', 'DSL')");
        jdbc.update("insert into user_roles_role (userId, roleId) values (9001, 9001)");
        jdbc.update("insert into role_permissions_permission (roleId, permissionId) values (9001, 1)");
        assertEquals(1, roles.findRolesByUserId(9001L).size());
        assertEquals(9001L, roles.findRolesByUserId(9001L).getFirst().getId());
        assertEquals(1, permissions.findByRoleId(9001L).size());
        assertEquals(1L, permissions.findByRoleId(9001L).getFirst().getId());
        assertTrue(roles.findRolesByUserId(9999L).isEmpty());
        assertTrue(permissions.findByRoleId(9999L).isEmpty());
    }

    @Test
    void roleAssignmentFiltersBothListsBeforePagingAndKeepsDepartments() throws Exception {
        jdbc.update("""
                insert into `user` (id, username, password, nameCn, enable) values
                (9101, 'other_role_user', 'hash', '穿梭甲', true),
                (9102, 'target_role_user', 'hash', '穿梭乙', true),
                (9103, 'shared_role_user', 'hash', '穿梭丙', false)
                """);
        jdbc.update("insert into role (id, code, name) values (9101, 'transfer_target', '目标角色'), (9102, 'transfer_other', '其他角色')");
        jdbc.update("insert into user_roles_role (userId, roleId) values (9101, 9102), (9102, 9101), (9103, 9101), (9103, 9102)");
        jdbc.update("insert into sys_department (id, name) values (9101, '主部门测试'), (9102, '兼岗部门测试')");
        jdbc.update("insert into sys_department_member (user_id, department_id) values (9102, 9101), (9102, 9102)");
        jdbc.update("update `user` set departmentId = 9101 where id = 9102");

        String token = loginAsAdmin();
        var assigned = data(getJson("/user", token, "roleId", 9101, "roleAssigned", true, "keyword", "穿梭", "pageSize", 1));
        assertEquals(2L, assigned.get("total").asLong());
        var first = assigned.get("pageData").get(0);
        assertEquals(9102L, first.get("id").asLong());
        assertEquals("主部门测试", first.get("departmentName").asString());
        assertEquals(2, first.get("departments").size());

        var secondPage = data(getJson("/user", token, "roleId", 9101, "roleAssigned", true,
                "keyword", "穿梭", "pageSize", 1, "pageNo", 2));
        assertEquals(9103L, secondPage.get("pageData").get(0).get("id").asLong());

        var available = data(getJson("/user", token, "roleId", 9101, "roleAssigned", false, "keyword", "穿梭"));
        assertEquals(1L, available.get("total").asLong());
        assertEquals(9101L, available.get("pageData").get(0).get("id").asLong());

        data(patchJson("/role/users/add/9101", token, map("userIds", List.of(9101))));
        data(patchJson("/role/users/remove/9101", token, map("userIds", List.of(9102))));
        var updated = data(getJson("/user", token, "roleId", 9101, "roleAssigned", false, "keyword", "穿梭"));
        assertEquals(1L, updated.get("total").asLong());
        assertEquals(9102L, updated.get("pageData").get(0).get("id").asLong());
    }

    @Test
    void missingMembershipIdsNeverBroadenQuery() {
        assertTrue(roles.findRolesByUserId(null).isEmpty());
        assertTrue(permissions.findByRoleId(null).isEmpty());
    }

}
