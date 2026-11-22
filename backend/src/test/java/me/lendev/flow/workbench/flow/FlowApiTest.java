package me.lendev.flow.workbench.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DirtiesContext
class FlowApiTest extends ApiTestBase {

    private static final String REVIEW_CODE = "REVIEW";
    private static final String DEFINITIONS = "/workbench/flow-definitions";
    private static final String FLOWS = "/workbench/flows";
    private static final String TODOS = "/workbench/todos";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void retiringTemplatePreservesRunningInstanceAndPublishedRevisionIsImmutable() throws Exception {
        String token = loginAsAdmin();
        var draft = map("code", REVIEW_CODE, "name", "Draft",
                "steps", List.of(map("stepNo", 1, "title", "Review", "assigneeUserId", 1)));
        var definition = data(postJson(DEFINITIONS, token, draft));
        long id = definition.path("id").asLong();
        String definitionPath = DEFINITIONS + "/" + id;
        var start = map("requestKey", "run-review", "definitionId", id, "title", "Review request");
        body(postJson(FLOWS, token, start), 400);
        body(postJson(DEFINITIONS, token, draft), 409);

        var edit = map("name", "  Ready for review  ", "version", 0, "steps",
                List.of(map("stepNo", 1, "title", "  Confirm  ", "assigneeUserId", 1, "dueOffsetDays", 0)));
        var edited = data(patchJson(definitionPath, token, edit));
        assertEquals("Ready for review", edited.path("name").asString());
        assertEquals("Confirm", edited.path("steps").get(0).path("title").asString());
        body(patchJson(definitionPath, token, edit), 409);
        edit.put("version", edited.path("version").asLong());
        assertEquals(edited, data(patchJson(definitionPath, token, edit)));
        assertEquals("NO_CHANGE", jdbc.queryForObject("""
                SELECT result FROM sys_audit_outbox
                WHERE operationCode = 'workbench.flow.definition.edit' ORDER BY id DESC LIMIT 1
                """, String.class));

        var published = data(postJson(definitionPath + "/publish", token, map("version", edited.path("version").asLong())));
        assertEquals("PUBLISHED", published.path("status").asString());
        assertEquals(published, data(getJson(DEFINITIONS, token, "published", true)).valueStream()
                .filter(item -> REVIEW_CODE.equals(item.path("code").asString())).findFirst().orElseThrow());
        edit.put("version", published.path("version").asLong());
        body(patchJson(definitionPath, token, edit), 409);

        var started = data(postJson(FLOWS, token, start));
        String flowPath = FLOWS + "/" + started.path("id").asLong();
        long todoId = started.path("steps").get(0).path("todoId").asLong();
        assertEquals("Confirm", data(getJson(TODOS + "/" + todoId, token)).path("title").asString());
        var retired = data(postJson(definitionPath + "/retire", token, map("version", published.path("version").asLong())));
        assertEquals("RETIRED", retired.path("status").asString());
        assertEquals(0, data(getJson(DEFINITIONS, token, "published", true)).valueStream()
                .filter(item -> REVIEW_CODE.equals(item.path("code").asString())).count());
        assertEquals(started, data(getJson(flowPath, token)));
        assertEquals(started, data(postJson(FLOWS, token, start)));
        assertEquals(List.of("TODO:" + todoId + ":TASK_ASSIGNED"), jdbc.queryForList("""
                SELECT source_key FROM sys_in_app_message
                WHERE target_type = 'WORKBENCH_TODO' AND target_id = ? AND recipient_user_id = 1
                """, String.class, todoId));
        assertTrue(data(getJson("/in-app-messages", token)).path("items").valueStream()
                .anyMatch(item -> ("/workbench/todos?todo=" + todoId).equals(item.path("targetUrl").asString())));
        start.put("title", "Changed request");
        body(postJson(FLOWS, token, start), 409);
        start.put("requestKey", "retired-review");
        body(postJson(FLOWS, token, start), 400);

        var secondRevision = data(postJson(DEFINITIONS, token, draft));
        assertEquals(2, secondRevision.path("revision").asInt());
        assertEquals(2, data(getJson(DEFINITIONS, token)).valueStream()
                .filter(item -> REVIEW_CODE.equals(item.path("code").asString())).count());
        var completed = data(postJson(flowPath + "/complete", token,
                map("todoId", todoId, "version", started.path("version").asLong())));
        assertEquals("DONE", completed.path("status").asString());
        assertEquals("DONE", completed.path("steps").get(0).path("status").asString());
        assertTrue(completed.path("allowedActions").isEmpty());
        assertEquals("DONE", data(getJson(TODOS + "/" + todoId, token)).path("status").asString());
    }

    @Test
    void activeReassignmentTransfersCompletionAndCancellationOnlyClosesTheActiveTodo() throws Exception {
        String admin = loginAsAdmin();
        var account = data(postJson("/user", admin, map("username", "flow_active_assignee", "roleIds", List.of(2))));
        int assignee = jdbc.queryForObject(
                "SELECT id FROM `user` WHERE username = 'flow_active_assignee'", Integer.class);
        jdbc.update("""
                INSERT INTO role_permissions_permission (roleId,permissionId)
                SELECT 2,id FROM permission WHERE code='WorkbenchTodo'
                """);
        String temporary = account.path("temporaryPassword").asString();
        String password = "Flow-Assignee34!";
        data(postJson("/auth/password", login("flow_active_assignee", temporary),
                map("oldPassword", temporary, "newPassword", password)));
        String member = login("flow_active_assignee", password);
        code(getJson("/notification-groups/workflow-deliveries", member), 11003);

        var definition = data(postJson(DEFINITIONS, admin, map("code", "CANCEL_FLOW", "name", "Sign-off",
                "steps", List.of(map("stepNo", 1, "title", "Draft", "assigneeUserId", 1, "dueOffsetDays", 0),
                        map("stepNo", 2, "title", "Sign off", "assigneeUserId", 1)))));
        long definitionId = definition.path("id").asLong();
        data(postJson(DEFINITIONS + "/" + definitionId + "/publish", admin,
                map("version", definition.path("version").asLong())));
        var start = map("requestKey", "cancel-signoff", "definitionId", definitionId, "title", "Quarter end",
                "assignees", Map.of("3", assignee));
        body(postJson(FLOWS, admin, start), 400);
        start.remove("assignees");
        var started = data(postJson(FLOWS, admin, start));
        String flowPath = FLOWS + "/" + started.path("id").asLong();
        long todoId = started.path("steps").get(0).path("todoId").asLong();
        String todoPath = TODOS + "/" + todoId;
        body(getJson(flowPath, member), 404);
        var reassignment = map("stepNo", 1, "assigneeUserId", assignee, "version", started.path("version").asLong());
        var reassigned = data(postJson(flowPath + "/assign", admin, reassignment));
        reassignment.put("version", reassigned.path("version").asLong());
        assertEquals(reassigned, data(postJson(flowPath + "/assign", admin, reassignment)));
        assertEquals(assignee, data(getJson(todoPath, member)).path("assigneeUserId").asInt());
        var viewed = data(getJson(flowPath, member));
        assertTrue(viewed.path("steps").get(0).path("dueOffsetDays").isNull());
        assertEquals(1, viewed.path("allowedActions").size());
        assertEquals("complete", viewed.path("allowedActions").get(0).asString());
        assertTrue(reassigned.path("allowedActions").valueStream().noneMatch(action -> "complete".equals(action.asString())));
        code(postJson(flowPath + "/complete", admin,
                map("todoId", todoId, "version", reassigned.path("version").asLong())), 11003);

        var cancel = map("reason", "  Sign-off no longer needed  ", "version", reassigned.path("version").asLong());
        code(postJson(flowPath + "/cancel", member, cancel), 11003);
        var cancelled = data(postJson(flowPath + "/cancel", admin, cancel));
        assertEquals("CANCELLED", cancelled.path("status").asString());
        assertEquals("CANCELLED", cancelled.path("steps").get(0).path("status").asString());
        assertEquals("WAITING", cancelled.path("steps").get(1).path("status").asString());
        assertTrue(cancelled.path("steps").get(1).path("todoId").isNull());
        assertEquals(cancelled, data(postJson(flowPath + "/cancel", admin, cancel)));
        cancel.put("version", cancelled.path("version").asLong());
        cancel.put("reason", "Different reason");
        body(postJson(flowPath + "/cancel", admin, cancel), 409);
        var todo = data(getJson(todoPath, member));
        assertEquals("CANCELLED", todo.path("status").asString());
        assertEquals("FLOW_CANCEL", jdbc.queryForObject("SELECT cancelKind FROM wb_todo WHERE id = ?",
                String.class, todoId));
        assertEquals("Sign-off no longer needed", jdbc.queryForObject("SELECT cancelReason FROM wb_todo WHERE id = ?",
                String.class, todoId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void unavailableNextAssigneeLeavesCurrentNodeOpen() throws Exception {
        String token = loginAsAdmin();
        jdbc.update("INSERT INTO `user` (username, password) VALUES ('flow_next_assignee', 'unused')");
        int nextAssignee = jdbc.queryForObject(
                "SELECT id FROM `user` WHERE username = 'flow_next_assignee'", Integer.class);
        var definition = data(postJson("/workbench/flow-definitions", token,
                map("code", "HANDOVER", "name", "Handover", "steps", List.of(
                        map("stepNo", 1, "title", "First", "assigneeUserId", 1),
                        map("stepNo", 2, "title", "Second", "assigneeUserId", nextAssignee)))));
        long definitionId = definition.path("id").asLong();
        data(postJson("/workbench/flow-definitions/" + definitionId + "/publish", token,
                map("version", definition.path("version").asLong())));
        var startRequest = map("requestKey", "handover", "definitionId", definitionId, "title", "Daily handover");
        var started = data(postJson("/workbench/flows", token, startRequest));
        long flowId = started.path("id").asLong();
        long todoId = started.path("steps").get(0).path("todoId").asLong();
        String flowPath = "/workbench/flows/" + flowId;

        jdbc.update("UPDATE `user` SET enable = 0 WHERE id = ?", nextAssignee);
        assertEquals(flowId, data(postJson("/workbench/flows", token, startRequest)).path("id").asLong());
        body(postJson(flowPath + "/complete", token,
                map("todoId", todoId, "version", started.path("version").asLong())), 400);

        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM wb_todo WHERE id = ?", String.class, todoId));
        assertEquals(1, jdbc.queryForObject("SELECT currentStepNo FROM wf_instance WHERE id = ?", Integer.class, flowId));
        var assigned = data(postJson(flowPath + "/assign", token, map("stepNo", 2, "assigneeUserId", 1,
                "version", started.path("version").asLong())));
        var completion = map("todoId", todoId, "version", assigned.path("version").asLong());
        var advanced = data(postJson(flowPath + "/complete", token, completion));
        assertEquals(2, advanced.path("currentStepNo").asInt());
        assertEquals(advanced.path("version").asLong(),
                data(postJson(flowPath + "/complete", token, completion)).path("version").asLong());
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wb_todo WHERE sourceModule = 'workflow' AND businessId = ?
                """, Integer.class, Long.toString(flowId)));
    }
}
