package me.lendev.flow.workbench.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class TodoApiTest extends ApiTestBase {

    private static final String TODOS = "/workbench/todos";
    private static final String PERSONAL = TODOS + "/personal-dimensions";
    private static final String CANCEL = "/actions/cancel";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TodoSchedule schedule;

    @Test
    void creationKeepsTheLoginUsersPrimaryDepartmentAcrossRetries() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("INSERT INTO sys_department (id, name) VALUES (7001, '发起部门'), (7002, '兼任部门')");
        jdbc.update("INSERT INTO sys_department_member (user_id, department_id) VALUES (1, 7001), (1, 7002)");
        jdbc.update("UPDATE `user` SET departmentId=7001 WHERE id=1");
        jdbc.update("INSERT INTO `user` (username, password) VALUES ('todo_department_assignee', 'unused')");
        int assignee = jdbc.queryForObject("SELECT id FROM `user` WHERE username='todo_department_assignee'", Integer.class);
        var request = map("requestKey", "todo-department", "title", "部门快照", "assigneeUserId", assignee);
        var created = data(postJson(TODOS, token, request));
        long id = created.path("id").asLong();
        assertEquals(7001L, created.path("departmentId").asLong());

        jdbc.update("UPDATE `user` SET departmentId=7002 WHERE id=1");
        assertEquals(7001L, data(postJson(TODOS, token, request)).path("departmentId").asLong());
        assertEquals(7001L, data(getJson(TODOS + "/" + id, token)).path("departmentId").asLong());
        assertEquals(7001L, jdbc.queryForObject("SELECT departmentId FROM wb_todo WHERE id=?", Long.class, id));
    }

    @Test
    void creationAllowsUsersWithoutAPrimaryDepartment() throws Exception {
        String token = loginAsAdmin();
        var created = data(postJson(TODOS, token, map("requestKey", "todo-without-department", "title", "无部门待办")));
        assertTrue(created.path("departmentId").isNull());
    }

    @Test
    void publicDimensionFiltersAndStatisticsUseTheConfiguredDictionary() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("INSERT INTO sys_param_group (code, name) VALUES ('WORKBENCH_TODO_DICT_REPORT', 'Report')");
        long dictionary = jdbc.queryForObject(
                "SELECT id FROM sys_param_group WHERE code = 'WORKBENCH_TODO_DICT_REPORT'", Long.class);
        jdbc.update("INSERT INTO sys_param_item (groupId, code, name) VALUES (?, 'REVIEW', 'Needs review')", dictionary);
        long option = jdbc.queryForObject("SELECT id FROM sys_param_item WHERE groupId = ?", Long.class, dictionary);
        jdbc.update("""
                UPDATE sys_param_item SET value1 = ?, enable = 1
                WHERE groupId = (SELECT id FROM sys_param_group WHERE code = 'WORKBENCH_TODO_DIMENSION')
                """, Long.toString(dictionary));
        data(postJson(TODOS, token, map("requestKey", "report-tagged", "title", "Report tagged",
                "scheduleMode", "FIXED", "dueDate", schedule.today(), "dimensions",
                Map.of(1, option, 2, option, 3, option, 4, option, 5, option))));
        var overdue = data(postJson(TODOS, token, map("requestKey", "report-overdue", "title", "Report overdue",
                "scheduleMode", "FIXED", "dueDate", schedule.today().minusDays(1))));
        data(postJson(TODOS, token, map("requestKey", "report-unscheduled", "title", "Report unscheduled")));
        for (int slot = 1; slot <= 5; slot++) {
            assertEquals(1, data(getJson(TODOS, token, "scope", "created", "public" + slot, option))
                    .path("total").asLong());
            var group = data(getJson(TODOS + "/statistics", token, "groupBy", "public:" + slot,
                    "public" + slot, option));
            assertEquals(1, group.size());
            assertEquals("Needs review", group.get(0).path("name").asString());
            assertEquals(1, group.get(0).path("open").asLong());
        }
        for (var status : List.of("TODAY", "OVERDUE", "UNSCHEDULED")) {
            assertEquals(1, data(getJson(TODOS, token, "status", status, "keyword", "Report"))
                    .path("total").asLong());
        }
        data(postJson(TODOS + "/" + overdue.path("id").asLong() + "/actions/complete", token,
                map("version", overdue.path("version").asLong())));
        assertEquals(1, data(getJson(TODOS, token, "scope", "done", "keyword", "Report")).path("total").asLong());
    }

    @Test
    void rejectsMalformedFiltersAndInconsistentScheduleWrites() throws Exception {
        String token = loginAsAdmin();
        for (var query : Map.of("scope", "unknown", "page", "NaN", "fromDate", "0999-01-01",
                "public1", "-1", "personal1", " ").entrySet()) {
            body(getJson(TODOS, token, query.getKey(), query.getValue()), 400);
        }
        body(getJson(TODOS + "/statistics", token, "groupBy", "public:6"), 400);
        var request = map("requestKey", "invalid-schedule", "title", "Invalid schedule");
        for (var changes : List.of(map("title", " "), map("priority", "MAYBE"), map("dimensions", Map.of(6, 1)),
                map("requestKey", "contains spaces"), map("scheduleMode", "UNKNOWN"),
                map("scheduleMode", "TRADING"), map("scheduleMode", "TRADING", "marketCode", "CN",
                        "tradeBaseDate", "2099-11-10", "startTradeOffset", 1),
                map("scheduleMode", "NONE", "dueDate", "2099-11-10"),
                map("scheduleMode", "FIXED", "startDate", "2099-11-10", "endDate", "2099-11-09"))) {
            var invalid = new java.util.HashMap<>(request);
            invalid.putAll(changes);
            body(postJson(TODOS, token, invalid), 400);
        }
        assertEquals(0, data(getJson(TODOS, token, "keyword", "Invalid schedule")).path("total").asLong());
    }

    @Test
    void creationRetryKeepsOneTodoAndAllCountsCreatorAssigneeOverlapOnce() throws Exception {
        String token = loginAsAdmin();
        var request = map("requestKey", "list-test", "title", "Date range test", "assigneeUserId", 1,
                "scheduleMode", "FIXED", "startDate", "2099-11-12", "endDate", "2099-11-13");
        var first = data(postJson("/workbench/todos", token, request));
        assertEquals(first.path("id"), data(postJson("/workbench/todos", token, request)).path("id"));
        var list = data(getJson("/workbench/todos", token, "scope", "all", "dateField", "plan",
                "fromDate", "2099-11-13", "toDate", "2099-11-13", "keyword", "Date range test"));
        assertEquals(1, list.path("total").asInt());
        assertEquals(1, list.path("scopeCounts").path("all").asInt());
        assertEquals(1, list.path("scopeCounts").path("created").asInt());
        assertEquals(1, list.path("scopeCounts").path("mine").asInt());
        request.put("title", "Different request");
        body(postJson("/workbench/todos", token, request), 409);
        var complete = data(postJson("/workbench/todos/" + first.path("id").asLong() + "/actions/complete",
                token, map("version", first.path("version").asLong())));
        assertEquals("DONE", complete.path("status").asString());
        assertTrue(complete.path("closedAt").isString());
    }

    @Test
    void nonzeroOffsetRejectsClosedTradingBase() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("""
                INSERT INTO sys_trading_calendar_day (marketItemId, calendarDate, calendarDate8, isTradingDay, source)
                SELECT p.id, DATE '2099-11-07', '20991107', 0, 'MANUAL'
                FROM sys_param_item p JOIN sys_param_group g ON g.id = p.groupId
                WHERE g.code = 'TRADING_MARKET' AND p.code = 'CN'
                """);
        code(postJson("/workbench/todos/schedule-preview", token,
                map("scheduleMode", "TRADING", "marketCode", "CN", "tradeBaseDate", "2099-11-07",
                        "dueTradeOffset", 1)), 12002);
    }

    @Test
    void tradingSaveRequiresMatchingPreviewAndUnrelatedEditsKeepResolvedDates() throws Exception {
        String token = loginAsAdmin();
        for (int day = 10; day <= 12; day++) {
            String date = "2099-11-" + day;
            jdbc.update("""
                    INSERT INTO sys_trading_calendar_day (marketItemId,calendarDate,calendarDate8,isTradingDay,source)
                    SELECT p.id,CAST(? AS DATE),?,1,'MANUAL'
                    FROM sys_param_item p JOIN sys_param_group g ON g.id=p.groupId
                    WHERE g.code='TRADING_MARKET' AND p.code='CN'
                    """, date, date.replace("-", ""));
        }
        var request = map("requestKey", "trade-preview", "title", "Review trade date", "scheduleMode", "TRADING",
                "marketCode", "CN", "tradeBaseDate", "2099-11-10", "dueTradeOffset", 1);
        body(postJson("/workbench/todos", token, request), 400);
        var preview = data(postJson("/workbench/todos/schedule-preview", token, request));
        assertEquals("2099-11-10", preview.path("coverageStart").asString());
        assertEquals("2099-11-11", preview.path("coverageEnd").asString());
        request.put("preview", map("dueDate", preview.path("dueDate").asString()));
        jdbc.update("UPDATE sys_trading_calendar_day SET isTradingDay=0 WHERE calendarDate=DATE '2099-11-11'");
        body(postJson("/workbench/todos", token, request), 409);
        request.put("preview", map("dueDate", "2099-11-12"));
        var saved = data(postJson("/workbench/todos", token, request));
        jdbc.update("UPDATE sys_trading_calendar_day SET isTradingDay=1 WHERE calendarDate=DATE '2099-11-11'");
        var edited = data(patchJson("/workbench/todos/" + saved.path("id").asLong(), token,
                map("title", "New title", "version", saved.path("version").asLong())));
        assertEquals("2099-11-12", edited.path("dueDate").asString());
    }

    @Test
    void creatorAndAssigneeAnnotateSameTodoInPrivateNamespaces() throws Exception {
        String admin = loginAsAdmin();
        var created = data(postJson("/user", admin, map("username", "todo_private_user", "roleIds", List.of(2))));
        int assignee = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Integer.class, "todo_private_user");
        jdbc.update("""
                INSERT INTO role_permissions_permission (roleId,permissionId)
                SELECT 2,id FROM permission WHERE code='WorkbenchTodo'
                """);
        String temporary = created.path("temporaryPassword").asString();
        String password = "Private-Test345!";
        String restricted = login("todo_private_user", temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", password)));
        String other = login("todo_private_user", password);

        long id = data(postJson("/workbench/todos", admin,
                map("requestKey", "personal-test", "title", "Private annotations", "assigneeUserId", assignee)))
                .path("id").asLong();
        String mine = configurePersonal(admin, "Owner batch");
        String theirs = configurePersonal(other, "Assignee batch");
        String todo = "/workbench/todos/" + id;
        data(patchJson(todo + "/personal-dimensions", admin, Map.of("1", mine)));
        body(patchJson(todo + "/personal-dimensions", other, Map.of("1", mine)), 400);
        data(patchJson(todo + "/personal-dimensions", other, Map.of("1", theirs)));
        assertEquals(mine, data(getJson(todo, admin)).path("personalDimensions").path("1").asString());
        assertEquals(theirs, data(getJson(todo, other)).path("personalDimensions").path("1").asString());
        assertEquals(0, data(getJson("/workbench/todos", admin, "scope", "all", "personal1", theirs))
                .path("total").asLong());
        assertEquals(1, data(getJson("/workbench/todos", other, "scope", "mine", "personal1", theirs))
                .path("scopeCounts").path("mine").asLong());
        assertEquals("Assignee batch", data(getJson("/workbench/todos/statistics", other,
                "scope", "mine", "groupBy", "personal:1", "personal1", theirs)).get(0).path("name").asString());
        code(getJson("/workbench/todos/manage/" + id, other), 11003);
    }

    @Test
    void manualTodoChangesScheduleStartsAndCancelsWithOptimisticVersions() throws Exception {
        String token = loginAsAdmin();
        var created = data(postJson(TODOS, token, map("requestKey", "todo-lifecycle", "title", "Review",
                "scheduleMode", "FIXED", "startDate", "2099-11-12", "endDate", "2099-11-13")));
        long id = created.path("id").asLong();
        String todo = TODOS + "/" + id;
        long initialVersion = created.path("version").asLong();
        body(patchJson(todo, token, map("title", "Missing version")), 409);
        body(patchJson(todo, token, map("version", initialVersion, "origin", "BUSINESS")), 400);
        assertEquals(initialVersion, data(patchJson(todo, token, map("title", "Review", "version", initialVersion)))
                .path("version").asLong());

        var unscheduled = data(patchJson(todo, token, map("scheduleMode", "NONE", "version", initialVersion)));
        assertEquals("NONE", unscheduled.path("scheduleMode").asString());
        assertTrue(unscheduled.path("startDate").isNull());
        assertTrue(unscheduled.path("endDate").isNull());
        body(postJson(todo + "/actions/start", token, map("version", initialVersion)), 409);
        var started = data(postJson(todo + "/actions/start", token, map("version", unscheduled.path("version").asLong())));
        assertEquals("IN_PROGRESS", started.path("status").asString());
        assertTrue(started.path("closedAt").isNull());
        long version = started.path("version").asLong();
        body(postJson(todo + CANCEL, token, map("version", version, "reason", "  ")), 400);
        var cancelled = data(postJson(todo + CANCEL, token, map("version", version, "reason", "  Superseded  ")));
        assertEquals("CANCELLED", cancelled.path("status").asString());
        assertTrue(cancelled.path("closedAt").isString());
        assertTrue(cancelled.path("allowedActions").isEmpty());
        assertEquals("Superseded", jdbc.queryForObject("SELECT cancelReason FROM wb_todo WHERE id = ?", String.class, id));
        body(postJson(TODOS + "/manage/" + id + "/assign", token,
                map("version", cancelled.path("version").asLong(), "assigneeUserId", 1)), 409);
    }

    @Test
    void assigneeChangesKeepTheCreatorAndRespectVisibilityAndVersions() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("INSERT INTO `user` (username, password) VALUES ('todo_reassigned', 'unused')");
        int assignee = jdbc.queryForObject("SELECT id FROM `user` WHERE username = 'todo_reassigned'", Integer.class);
        var people = data(getJson(TODOS + "/assignees", token));
        assertTrue(people.valueStream().anyMatch(person -> person.path("id").asInt() == assignee));
        var created = data(postJson(TODOS, token, map("requestKey", "todo-reassignment", "title", "Review")));
        long id = created.path("id").asLong();
        String todo = TODOS + "/" + id;
        String manage = TODOS + "/manage/" + id;
        long version = created.path("version").asLong();
        assertEquals(version, data(postJson(todo + "/actions/assign", token,
                map("version", version, "assigneeUserId", 1))).path("version").asLong());
        var assigned = data(postJson(todo + "/actions/assign", token, map("version", version, "assigneeUserId", assignee)));
        assertEquals(assignee, assigned.path("assigneeUserId").asInt());
        assertEquals(1, assigned.path("ownerUserId").asInt());
        assertEquals(assignee, data(getJson(manage, token)).path("assigneeUserId").asInt());
        body(postJson(manage + "/assign", token, map("version", version, "assigneeUserId", 1)), 409);
        body(postJson(todo + "/actions/complete", token, map("version", assigned.path("version").asLong())), 404);
        var returned = data(postJson(manage + "/assign", token,
                map("version", assigned.path("version").asLong(), "assigneeUserId", 1)));
        assertEquals(1, returned.path("assigneeUserId").asInt());
        assertEquals(1, returned.path("ownerUserId").asInt());
    }

    @Test
    void calendarKeepsCompletedTodosOnlyWhenRequestedAndRejectsOversizedRanges() throws Exception {
        String token = loginAsAdmin();
        var first = data(postJson(TODOS, token, map("requestKey", "calendar-finished", "title", "Finished",
                "scheduleMode", "FIXED", "dueDate", "2099-12-03")));
        long firstId = first.path("id").asLong();
        data(postJson(TODOS + "/" + firstId + "/actions/complete", token, map("version", first.path("version").asLong())));
        var second = data(postJson(TODOS, token, map("requestKey", "calendar-cancelled", "title", "Cancelled",
                "scheduleMode", "FIXED", "dueDate", "2099-12-03")));
        data(postJson(TODOS + "/" + second.path("id").asLong() + CANCEL, token,
                map("version", second.path("version").asLong(), "reason", "Superseded")));
        String calendar = TODOS + "/calendar";
        body(getJson(calendar, token, "fromDate", "2099-12-01", "toDate", "2100-01-12"), 400);
        assertTrue(data(getJson(calendar, token, "fromDate", "2099-12-01", "toDate", "2099-12-03")).isEmpty());
        var included = data(getJson(calendar, token, "fromDate", "2099-12-01", "toDate", "2099-12-03",
                "includeCompleted", true));
        assertEquals(1, included.size());
        assertEquals(firstId, included.get(0).path("id").asLong());
        assertEquals("DONE", included.get(0).path("status").asString());
    }

    @Test
    void createsTodoWithTextAndSelectedPersonalDimensions() throws Exception {
        String token = loginAsAdmin();
        String option = configurePersonal(token, "Priority project");
        data(mockMvc.perform(put(PERSONAL + "/2")
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(map("name", "Note", "enabled", true, "options", List.of())))));
        String text = "This private note is longer than the old option key limit: 445566";
        var request = map("requestKey", "personal-on-create", "title", "Tagged at creation",
                "personalDimensions", Map.of("1", option, "2", "  " + text + "  "));
        var saved = data(postJson(TODOS, token, request));
        long id = saved.path("id").asLong();
        assertEquals(option, saved.path("personalDimensions").path("1").asString());
        assertEquals(text, saved.path("personalDimensions").path("2").asString());
        assertEquals(id, data(postJson(TODOS, token, request)).path("id").asLong());
        assertEquals(1, data(getJson(TODOS, token, "personal2", text)).path("total").asLong());
        var matches = data(getJson(TODOS, token, "personal1", option, "personal2", "5566"));
        assertEquals(1, matches.path("scopeCounts").path("mine").asLong());
        assertEquals(id, matches.path("records").get(0).path("id").asLong());
        assertEquals(0, data(getJson(TODOS, token, "personal1", option.substring(0, 8)))
                .path("total").asLong());
        assertEquals(0, data(getJson(TODOS, token, "personal2", "44%66")).path("total").asLong());
        assertEquals(text, data(getJson(TODOS + "/statistics", token,
                "groupBy", "personal:2", "personal2", "5566")).get(0).path("name").asString());
        var changed = data(patchJson(TODOS + "/" + id + "/personal-dimensions", token, map("2", " Updated ")));
        assertEquals("Updated", changed.path("personalDimensions").path("2").asString());
        assertEquals("Updated", data(postJson(TODOS, token, request)).path("personalDimensions").path("2").asString());
        request.put("personalDimensions", Map.of("1", option, "2", "Different"));
        body(postJson(TODOS, token, request), 409);
    }

    @Test
    void referencedPersonalOptionSurvivesRenameAndCanBeRemovedAfterClearingTag() throws Exception {
        String token = loginAsAdmin();
        String key = configurePersonal(token, "First batch");
        String next = "60bb3fd9-ac55-46f9-95bb-e06c75d934a9";
        var original = map("key", key, "name", "Renamed", "enabled", true);
        var replacement = map("key", next, "name", "Second batch", "enabled", true);
        var definition = map("name", "Batch", "enabled", true, "version", 0, "options", List.of(original, replacement));
        var todo = data(postJson(TODOS, token, map("requestKey", "personal-edit", "title", "Review")));
        String tags = TODOS + "/" + todo.path("id").asLong() + "/personal-dimensions";
        data(patchJson(tags, token, map("1", key)));
        var configured = data(putPersonal(token, definition));
        assertEquals(configured, data(getJson(PERSONAL, token)).get(0));
        body(putPersonal(token, definition), 409);
        definition.put("version", configured.path("version").asLong());
        assertEquals(configured, data(putPersonal(token, definition)));
        definition.put("options", List.of(replacement));
        body(putPersonal(token, definition), 400);

        var retagged = data(patchJson(tags, token, map("1", next)));
        assertEquals(next, retagged.path("personalDimensions").path("1").asString());
        definition.put("options", List.of(original));
        body(putPersonal(token, definition), 400);
        var cleared = data(patchJson(tags, token, map("1", null)));
        assertTrue(cleared.path("personalDimensions").isEmpty());
        var remaining = data(putPersonal(token, definition)).path("options");
        assertEquals(1, remaining.size());
        assertEquals(key, remaining.get(0).path("key").asString());
        body(patchJson(tags, token, map("1", next)), 400);
    }

    private String configurePersonal(String token, String name) throws Exception {
        var body = map("name", "Batch", "enabled", true, "options", List.of(map("name", name, "enabled", true)));
        return data(putPersonal(token, body)).path("options").get(0).path("key").asString();
    }

    private ResultActions putPersonal(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(PERSONAL + "/1")
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
