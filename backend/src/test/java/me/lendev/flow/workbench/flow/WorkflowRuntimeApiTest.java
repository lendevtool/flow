package me.lendev.flow.workbench.flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import me.lendev.flow.pms.inapp.InAppMessageService;
import me.lendev.flow.pms.inapp.InAppMessageService.Write;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

@Sql("/db/seed/workflow-examples.sql")
class WorkflowRuntimeApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InAppMessageService inbox;

    @Test
    void notifiesNewlyCompletedChildrenAndRetainsTheReturnedReasonWhenRetryingAssignment() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        String scene = "workflow.test.progress";
        jdbc.update("""
                INSERT INTO sys_notification_scene
                    (scene_code, name_cn, name_en, scene_type, enabled, in_app_title_template, updated_at)
                VALUES (?, '流程进展', 'Workflow progress', 'WORKFLOW_EXTRA', TRUE, 'Workflow progress', CURRENT_TIMESTAMP)
                """, scene);
        var nodes = List.of(
                map("key", "parallel", "order", 1, "title", "Parallel", "onApprovedSceneCode", scene),
                map("key", "budget", "parentKey", "parallel", "order", 1, "title", "Budget",
                        "roleCode", "WF_DEMO_BUDGET", "mode", "BROADCAST", "onApprovedSceneCode", scene),
                map("key", "policy", "parentKey", "parallel", "order", 2, "title", "Policy",
                        "roleCode", "WF_DEMO_SECURITY", "mode", "BROADCAST", "onApprovedSceneCode", scene),
                map("key", "review", "order", 2, "title", "Review", "roleCode", "WF_DEMO_REQUESTER",
                        "mode", "BROADCAST", "onRejectedSceneCode", scene));
        var draft = data(postJson("/workflow/definitions", admin, map("code", "NOTICE_PARALLEL", "name", "Parallel",
                "fields", List.of(), "nodes", nodes)));
        long definition = publish(admin, draft.path("code").asString());
        var started = start(admin, definition, "notice-start");
        String flow = path(started);
        long instanceId = started.path("instance").path("id").asLong();
        assertEquals(2L, messages(instanceId, "TASK_ASSIGNED"));
        inbox.publish(new Write(1, "TEST", "workflow:target:" + instanceId, "NODE_APPROVED", "流程进展",
                null, "WORKFLOW_INSTANCE", instanceId));
        var personal = data(getJson("/in-app-messages", admin)).path("items");
        assertTrue(personal.valueStream().anyMatch(message ->
                ("/workbench/workflow-task/" + instanceId).equals(message.path("targetUrl").asString())));
        assertTrue(personal.valueStream().anyMatch(message ->
                ("/workbench/workflow-task/" + instanceId + "?mode=task&todo=" + task(started, "budget"))
                        .equals(message.path("targetUrl").asString())));
        var afterBudget = submit(admin, flow, started, "budget", "notice-budget");
        assertEquals(List.of("budget"), notifiedNodes(instanceId, "NODE_APPROVED"));
        var reviewing = submit(admin, flow, afterBudget, "policy", "notice-policy");
        assertEquals(List.of("budget", "parallel", "policy"), notifiedNodes(instanceId, "NODE_APPROVED"));
        assertEquals(3L, messages(instanceId, "TASK_ASSIGNED"));

        jdbc.update("DELETE FROM user_roles_role WHERE roleId IN (SELECT id FROM role WHERE code = 'WF_DEMO_BUDGET')");
        var reject = map("requestKey", "notice-reject", "version", version(reviewing), "todoId", task(reviewing, "review"),
                "targetNodeKey", "budget", "reason", "Restricted rejection text");
        var waiting = data(postJson(flow + "/actions/reject", admin, reject));
        assertEquals(waiting, data(postJson(flow + "/actions/reject", admin, reject)));
        assertEquals(List.of("review"), notifiedNodes(instanceId, "NODE_REJECTED"));
        assertEquals(0L, messages(instanceId, "TASK_RETURNED"));
        assertEquals(List.of("budget", "parallel"), jdbc.queryForList("""
                SELECT nodeKey FROM wf_node_instance WHERE flowInstanceId = ? AND assignmentReason = 'RETURNED'
                ORDER BY nodeKey
                """, String.class, instanceId));

        jdbc.update("INSERT INTO user_roles_role (userId, roleId) SELECT 1,id FROM role WHERE code = 'WF_DEMO_BUDGET'");
        var retried = data(postJson(flow + "/actions/retry-assignment", admin,
                map("requestKey", "notice-retry", "version", version(waiting))));
        assertEquals(1L, messages(instanceId, "TASK_RETURNED"));
        assertEquals(List.of("review"), notifiedNodes(instanceId, "NODE_REJECTED"));
        submit(admin, flow, retried, "budget", "notice-budget-again");
        assertEquals(List.of("budget", "budget", "parallel", "parallel", "policy"),
                notifiedNodes(instanceId, "NODE_APPROVED"));
        verifyDeliveryQuery(admin, instanceId, scene);
    }

    private void verifyDeliveryQuery(String token, long instanceId, String scene) throws Exception {
        String route = "/notification-groups/workflow-deliveries";
        var page = data(getJson(route, token, "sceneCode", scene, "flowInstanceId", instanceId, "status", "PENDING",
                "from", "2000-01-01T00:00:00Z", "to", "2100-01-01T00:00:00Z", "pageSize", 2));
        assertEquals(6, page.path("total").asInt());
        assertEquals(2, page.path("pageData").size());
        var delivery = page.path("pageData").get(0);
        var detail = data(getJson(route + "/" + delivery.path("id").asLong(), token));
        assertEquals(delivery, detail.path("delivery"));
        assertTrue(detail.path("recipients").isEmpty());
        assertFalse(detail.toString().contains("Restricted rejection text"));
        assertEquals(0, data(getJson(route, token, "status", "DONE")).path("total").asInt());
        body(getJson(route + "/" + Long.MAX_VALUE, token), 404);
        body(getJson(route, token, "from", "2100-01-01T00:00:00Z", "to", "2000-01-01T00:00:00Z"), 400);
    }

    private long messages(long instanceId, String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM sys_in_app_message m
                JOIN wf_node_task t ON t.id = m.target_id
                JOIN wf_node_instance n ON n.id = t.nodeInstanceId
                WHERE m.target_type = 'WORKFLOW_TASK' AND n.flowInstanceId = ? AND m.message_type = ?
                """, Long.class, instanceId, type);
    }

    private List<String> notifiedNodes(long instanceId, String kind) {
        return jdbc.queryForList("""
                SELECT n.nodeKey
                FROM wf_notification_delivery d JOIN wf_node_instance n ON n.id = d.node_instance_id
                WHERE d.flow_instance_id = ? AND d.kind = ?
                ORDER BY n.nodeKey
                """, String.class, instanceId, kind);
    }

    @Test
    void dispatchesParallelNodesAndRecallsOnlyTheLastChild() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        long definition = publish(admin, "WF_DEMO_PURCHASE");
        var started = start(admin, definition, "PARALLEL");
        String flow = path(started);
        long first = task(started, "request");
        var invalid = map("requestKey", "missing", "version", version(started), "todoId", first);
        body(postJson(flow + "/actions/submit", admin, invalid), 400);
        assertEquals("OPEN", data(getJson("/workbench/todos/" + first, admin)).path("status").asString());
        assertTrue(data(getJson("/workbench/todos/" + first, admin)).path("sourceUrl").asString().contains("mode=task"));

        var input = map("requestKey", "request", "version", version(started), "todoId", first,
                "fields", map("wf_demo_subject", "Purchase", "wf_demo_amount", "3.00"));
        var parallel = data(postJson(flow + "/actions/submit", admin, input));
        assertEquals(parallel.path("instance"), data(postJson(flow + "/actions/submit", admin, input)).path("instance"));
        assertEquals(2, parallel.path("instance").path("currentStepNo").asInt());
        assertEquals(2, parallel.path("instance").path("currentNodes").size());
        assertEquals(1, parallel.path("plannedAssignees").path("finance").size());
        var afterBudget = submit(admin, flow, parallel, "budget", "budget");
        assertEquals(2, afterBudget.path("instance").path("currentStepNo").asInt());
        var current = data(getJson("/workflow/instances", admin, "scope", "STARTED")).path("pageData").get(0)
                .path("currentNodes");
        assertEquals(1, current.size());
        assertEquals("compliance", current.get(0).path("nodeKey").asString());
        assertEquals(1, current.get(0).path("assigneeNames").size());
        assertEquals(1, node(afterBudget, "budget").path("processedNames").size());
        assertTrue(node(afterBudget, "budget").path("assigneeNames").isEmpty());
        var finance = submit(admin, flow, afterBudget, "compliance", "compliance");
        assertEquals(3, finance.path("instance").path("currentStepNo").asInt());
        assertTrue(finance.path("canRecall").asBoolean());
        long financeTodo = task(finance, "finance");
        var recalled = data(postJson(flow + "/actions/recall", admin, map("requestKey", "recall", "version", version(finance))));
        assertEquals(2, recalled.path("instance").path("currentStepNo").asInt());
        assertTrue(recalled.path("tasks").valueStream().noneMatch(item -> item.path("nodeKey").asString().equals("budget") && item.path("canSubmit").asBoolean()));
        assertEquals("CANCELLED", data(getJson("/workbench/todos/" + financeTodo, admin)).path("status").asString());

        var again = submit(admin, flow, recalled, "compliance", "compliance-again");
        var returned = data(postJson(flow + "/actions/reject", admin, map("requestKey", "return-budget", "version", version(again),
                "todoId", task(again, "finance"), "targetNodeKey", "budget", "reason", "Check again")));
        assertTrue(returned.path("tasks").valueStream().noneMatch(item -> item.path("nodeKey").asString().equals("compliance") && item.path("canSubmit").asBoolean()));
        var ready = submit(admin, flow, returned, "budget", "budget-again");
        var done = submit(admin, flow, ready, "finance", "finance");
        assertEquals("DONE", done.path("instance").path("status").asString());
        assertTrue(done.path("instance").path("currentNodes").isEmpty());
        var completed = data(getJson("/workflow/instances", admin, "scope", "DONE"));
        assertEquals(1, completed.path("total").asInt());
        assertEquals(1, completed.path("scopeCounts").path("APPROVED").asInt());
        assertEquals(0, completed.path("scopeCounts").path("MINE").asInt());
        assertTrue(data(getJson(flow + "/history", admin)).path("total").asInt() >= 7);
    }

    @Test
    void filtersWorkflowPagesAndCountsAcrossTabs() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        long definition = publish(admin, "WF_DEMO_PAYMENT");
        var first = start(admin, definition, "SEARCH-FIRST").path("instance");
        var second = start(admin, definition, "SEARCH-SECOND").path("instance");
        jdbc.update("UPDATE wf_instance SET title = ?, ownerName = ?, startedAt = ? WHERE id = ?",
                "Payment Review", "Finance Reviewer", LocalDateTime.parse("2026-11-10T23:59:59.999"), first.path("id").asLong());
        jdbc.update("UPDATE wf_instance SET title = ?, ownerName = ?, startedAt = ? WHERE id = ?",
                "Payment Checklist", "Operations Reviewer", LocalDateTime.parse("2026-11-11T00:00:00"), second.path("id").asLong());

        var unfiltered = data(getJson("/workflow/instances", admin, "pageSize", "1"));
        assertEquals(1, unfiltered.path("pageData").size());
        assertEquals(2, unfiltered.path("total").asInt());
        var counts = unfiltered.path("scopeCounts");
        assertEquals(2, counts.path("MINE").asInt());
        assertEquals(2, counts.path("STARTED").asInt());
        assertEquals(0, counts.path("APPROVED").asInt());
        assertEquals(0, counts.path("DONE").asInt());
        assertEquals(2, counts.path("ALL").asInt());

        var filtered = data(getJson("/workflow/instances", admin, "scope", "STARTED", "title", "  Payment  ",
                "ownerName", "Reviewer", "startedFrom", "2026-11-10", "startedTo", "2026-11-10"));
        assertEquals(1, filtered.path("total").asInt());
        assertEquals(first.path("id"), filtered.path("pageData").get(0).path("id"));
        assertEquals(1, filtered.path("scopeCounts").path("ALL").asInt());

        var noMatch = data(getJson("/workflow/instances", admin, "title", "Checklist", "ownerName", "Finance"));
        assertEquals(0, noMatch.path("total").asInt());
        assertEquals(0, noMatch.path("scopeCounts").path("ALL").asInt());
        body(getJson("/workflow/instances", admin, "startedFrom", "2026-11-11", "startedTo", "2026-11-10"), 400);
    }

    @Test
    void supportsPrimarySettingsAndValidatesForwarding() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        long role = jdbc.queryForObject("SELECT id FROM role WHERE code = 'WF_DEMO_REQUESTER'", Long.class);
        String handlers = "/workflow/roles/" + role + "/handlers";
        var changed = data(mockMvc.perform(put(handlers).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(writeJson(map("primaryUserId", 1, "backupUserIds", List.of())))));
        assertEquals(1, changed.path("primaryUserId").asInt());
        var started = start(admin, publish(admin, "WF_DEMO_PAYMENT"), "FORWARD");
        int delegate = createMember(admin, "flow_delegate_test");
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var delegation = data(postJson("/workflow/delegations", admin, map("delegateUserId", delegate,
                "startsAt", now.minusHours(1).toString(), "endsAt", now.plusHours(1).toString())));
        String api = "/workflow/delegations/" + delegation.path("id").asLong();
        assertEquals(1, data(getJson(api, admin)).path("pending").size());
        var forward = map("todoIds", List.of(task(started, "request")));
        assertEquals("FORWARDED", data(postJson(api + "/forward", admin, forward)).get(0).path("status").asString());
        assertEquals("ALREADY_FORWARDED", data(postJson(api + "/forward", admin, forward)).get(0).path("status").asString());
        assertEquals(1, data(getJson(api, admin)).path("forwarded").size());
        var parallel = start(admin, started.path("instance").path("definitionId").asLong(), "AFTER-DELEGATION");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wb_todo WHERE businessId = ? AND origin='FLOW'",
                Integer.class, parallel.path("instance").path("id").asString()));
    }

    @Test
    void reportsConfiguredHandlersAndWhetherTheRoleNeedsAnActivePrimary() throws Exception {
        String admin = loginAsAdmin();
        long requester = jdbc.queryForObject("SELECT id FROM role WHERE code='WF_DEMO_REQUESTER'", Long.class);
        long security = jdbc.queryForObject("SELECT id FROM role WHERE code='WF_DEMO_SECURITY'", Long.class);
        String handlers = "/workflow/roles/" + requester + "/handlers";
        String summary = handlers + "/summary";
        var missing = data(getJson(summary, admin));
        assertTrue(missing.path("requiresPrimaryHandler").asBoolean());
        assertTrue(missing.path("primaryUserId").isNull());
        assertFalse(missing.path("primaryEnabled").asBoolean());
        assertFalse(data(getJson("/workflow/roles/" + security + "/handlers/summary", admin))
                .path("requiresPrimaryHandler").asBoolean());

        int primary = createMember(admin, "flow_summary_main");
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) VALUES (?, ?), (1, ?)", primary, requester, requester);
        data(mockMvc.perform(put(handlers).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("primaryUserId", primary, "backupUserIds", List.of(1))))));
        var configured = data(getJson(summary, admin));
        assertEquals(primary, configured.path("primaryUserId").asInt());
        assertEquals(List.of(1), configured.path("backupUserIds").valueStream().map(JsonNode::asInt).toList());
        assertTrue(configured.path("primaryEnabled").asBoolean());

        jdbc.update("UPDATE `user` SET enable = FALSE WHERE id = ?", primary);
        assertFalse(data(getJson(summary, admin)).path("primaryEnabled").asBoolean());
        jdbc.update("DELETE FROM user_roles_role WHERE userId = ? AND roleId = ?", primary, requester);
        assertTrue(data(getJson(summary, admin)).path("primaryUserId").isNull());
    }

    @Test
    void retiredTemplateStillNeedsPrimaryWhileItsInstanceIsRunning() throws Exception {
        String admin = loginAsAdmin();
        long role = jdbc.queryForObject("SELECT id FROM role WHERE code='ROLE_QA'", Long.class);
        String summary = "/workflow/roles/" + role + "/handlers/summary";
        assertFalse(data(getJson(summary, admin)).path("requiresPrimaryHandler").asBoolean());
        jdbc.update("INSERT INTO wf_role_handler (userId, roleId, kind, primaryRoleId) VALUES (1, ?, 'PRIMARY', ?)", role, role);
        var draft = data(postJson("/workflow/definitions", admin, map("code", "RETIRED_ACTIVE", "name", "Review", "fields", List.of(),
                "nodes", List.of(map("key", "review", "order", 1, "title", "Review", "mode", "PRIMARY", "roleCode", "ROLE_QA")))));
        long definition = publish(admin, draft.path("code").asString());
        var started = start(admin, definition, "retired-active");
        data(postJson("/workflow/definitions/" + definition + "/retire", admin, map("version", 1)));
        assertTrue(data(getJson(summary, admin)).path("requiresPrimaryHandler").asBoolean());
        data(postJson(path(started) + "/actions/cancel", admin,
                map("requestKey", "cancel", "version", version(started), "reason", "Ended")));
        assertFalse(data(getJson(summary, admin)).path("requiresPrimaryHandler").asBoolean());
    }

    @Test
    void delegationCandidatesPreferEnabledBackupsAmongKeywordMatches() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        int regular = createMember(admin, "flow_pick_regular");
        int backup = createMember(admin, "flow_pick_backup");
        int disabled = createMember(admin, "flow_pick_disabled");
        jdbc.update("UPDATE `user` SET enable = FALSE WHERE id = ?", disabled);
        long role = jdbc.queryForObject("SELECT id FROM role WHERE code = 'WF_DEMO_REQUESTER'", Long.class);
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) VALUES (?, ?), (?, ?)", backup, role, disabled, role);
        String handlers = "/workflow/roles/" + role + "/handlers";
        body(mockMvc.perform(put(handlers).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("primaryUserId", 1, "backupUserIds", List.of(disabled))))), 400);
        data(mockMvc.perform(put(handlers).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(map("primaryUserId", 1, "backupUserIds", List.of(backup))))));

        assertEquals(List.of(backup), data(getJson(handlers, admin)).path("backupUserIds").valueStream()
                .map(JsonNode::asInt).toList());
        String candidates = "/workflow/delegations/candidates";
        assertEquals(List.of(backup, regular), data(getJson(candidates, admin, "keyword", "  flow_pick  "))
                .valueStream().map(user -> user.path("id").asInt()).toList());
        assertTrue(data(getJson(candidates, admin)).valueStream().noneMatch(user -> user.path("id").asInt() == 1));
    }

    @Test
    void cancelledDelegationStaysInHistoryAndRejectsForwarding() throws Exception {
        String admin = loginAsAdmin();
        int delegate = createMember(admin, "flow_cancel_user");
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String delegations = "/workflow/delegations";
        var period = map("delegateUserId", delegate, "startsAt", now.plusHours(1), "endsAt", now.plusHours(2));
        var scheduled = data(postJson(delegations, admin, period));
        assertEquals("SCHEDULED", scheduled.path("status").asString());
        body(postJson(delegations, admin, period), 400);

        String api = delegations + "/" + scheduled.path("id").asLong();
        var cancelled = data(postJson(api + "/cancel", admin, null));
        assertEquals("CANCELLED", cancelled.path("status").asString());
        assertFalse(cancelled.path("cancelledAt").asString("").isBlank());
        assertEquals(cancelled, data(postJson(api + "/cancel", admin, null)));
        assertEquals(cancelled, data(getJson(delegations, admin)).get(0));
        assertTrue(data(getJson(api, admin)).path("pending").isEmpty());
        body(postJson(api + "/forward", admin, map("todoIds", List.of(1))), 409);
        assertEquals("SCHEDULED", data(postJson(delegations, admin, period)).path("status").asString());
    }

    @Test
    void freezesCountersignRecipientsAndPreventsRecallAfterAnyoneSubmits() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        String username = "workflow_countersign";
        String initialPassword = data(postJson("/user", admin, map("username", username, "roleIds", List.of(2))))
                .path("temporaryPassword").asString();
        String password = "Workflow-Review25!";
        data(postJson("/auth/password", login(username, initialPassword),
                map("oldPassword", initialPassword, "newPassword", password)));
        String reviewer = login(username, password);
        int person = jdbc.queryForObject("SELECT id FROM `user` WHERE username = ?", Integer.class, username);
        var started = start(admin, publish(admin, "WF_DEMO_ACCESS"), "ALL");
        var awaiting = data(postJson(path(started) + "/actions/submit", admin, map("requestKey", "start-access",
                "version", version(started), "todoId", task(started, "request"),
                "fields", map("wf_demo_subject", "Permit", "wf_demo_description", "Contractor"))));
        // A later role membership change cannot join an active countersign round.
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) SELECT ?, id FROM role WHERE code='WF_DEMO_SECURITY'", person);
        body(getJson(path(started), reviewer), 404);
        var hidden = data(getJson("/workflow/instances", reviewer, "scope", "ALL"));
        assertEquals(0, hidden.path("total").asInt());
        assertEquals(0, hidden.path("scopeCounts").path("ALL").asInt());
        var returned = data(postJson(path(started) + "/actions/reject", admin, map("requestKey", "return-first",
                "version", version(awaiting), "todoId", task(awaiting, "security"), "reason", "Recheck")));
        var all = submit(admin, path(started), returned, "request", "start-access-again");
        assertTrue(all.path("canRecall").asBoolean());
        var first = submit(admin, path(started), all, "security", "first-review");
        assertEquals(2, first.path("instance").path("currentStepNo").asInt());
        assertFalse(first.path("canRecall").asBoolean());
        var security = node(first, "security");
        assertEquals(1, security.path("processedNames").size());
        assertEquals(username, security.path("assigneeNames").get(0).asString());
        body(postJson(path(started) + "/actions/recall", admin,
                map("requestKey", "too-late", "version", version(first))), 409);
        var other = data(getJson(path(started), reviewer));
        var advanced = submit(reviewer, path(started), other, "security", "second-review");
        assertTrue(advanced.path("canRecall").asBoolean());
        var recalled = data(postJson(path(started) + "/actions/recall", reviewer,
                map("requestKey", "recall-last-review", "version", version(advanced))));
        assertEquals(2, recalled.path("instance").path("currentStepNo").asInt());
        assertEquals(0, data(getJson(path(started), admin)).path("tasks").valueStream()
                .filter(task -> task.path("canSubmit").asBoolean()).count());
        var finalReview = submit(reviewer, path(started), recalled, "security", "last-review");
        assertEquals(3, finalReview.path("instance").path("currentStepNo").asInt());
    }

    @Test
    void countersignsAllSelectedRolesAndDeduplicatesOverlappingMembers() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        String username = "flow_multi_signer";
        var member = data(postJson("/user", admin, map("username", username, "roleIds", List.of(2))));
        String initialPassword = member.path("temporaryPassword").asString();
        String password = "Workflow-Review25!";
        data(postJson("/auth/password", login(username, initialPassword),
                map("oldPassword", initialPassword, "newPassword", password)));
        String reviewer = login(username, password);
        int person = jdbc.queryForObject("SELECT id FROM `user` WHERE username = ?", Integer.class, username);
        jdbc.update("INSERT INTO role(code, name, enable) VALUES ('WF_REVIEW_SECOND', 'Second Review', 1)");

        var review = map("key", "review", "order", 2, "title", "Joint Review", "mode", "MULTI_ALL",
                "roleCodes", List.of("WF_DEMO_SECURITY"));
        var draft = map("code", "WF_MULTI_REVIEW", "name", "Joint Review", "fields", List.of("wf_demo_subject"),
                "nodes", List.of(
                        map("key", "request", "order", 1, "title", "Submit", "mode", "PRIMARY", "roleCode", "WF_DEMO_REQUESTER"),
                        review,
                        map("key", "finish", "order", 3, "title", "Finish", "mode", "PRIMARY", "roleCode", "WF_DEMO_OPS")));
        body(postJson("/workflow/definitions", admin, draft), 400);
        review.put("roleCodes", List.of("WF_DEMO_SECURITY", "WF_DEMO_SECURITY"));
        body(postJson("/workflow/definitions", admin, draft), 400);
        review.put("roleCodes", List.of("WF_DEMO_SECURITY", "WF_REVIEW_SECOND"));
        review.put("editableFields", List.of("wf_demo_subject"));
        body(postJson("/workflow/definitions", admin, draft), 400);
        review.remove("editableFields");
        var emptyRole = data(getJson("/workflow/definitions/roles", admin)).valueStream()
                .filter(role -> role.path("code").asString().equals("WF_REVIEW_SECOND")).findFirst().orElseThrow();
        assertFalse(emptyRole.path("hasEnabledMembers").asBoolean());
        var missingAtSave = body(postJson("/workflow/definitions", admin, draft), 400);
        assertTrue(missingAtSave.path("message").asString().contains("Second Review"));
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) SELECT ?, id FROM role WHERE code='WF_REVIEW_SECOND'", 1);
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) SELECT ?, id FROM role WHERE code='WF_REVIEW_SECOND'", person);
        var saved = data(postJson("/workflow/definitions", admin, draft));
        long definition = saved.path("id").asLong();
        String publish = "/workflow/definitions/" + definition + "/publish";
        var publishInput = map("version", saved.path("version").asLong());
        jdbc.update("UPDATE role SET enable = 0 WHERE code = 'WF_REVIEW_SECOND'");
        assertTrue(body(postJson(publish, admin, publishInput), 400).path("message").asString().contains("Second Review"));
        jdbc.update("UPDATE role SET enable = 1 WHERE code = 'WF_REVIEW_SECOND'");
        data(postJson(publish, admin, publishInput));

        var started = start(admin, definition, "MULTI");
        var flow = path(started);
        var assigned = submit(admin, flow, started, "request", "multi-request");
        assertEquals(2, node(assigned, "review").path("assigneeNames").size());
        var first = submit(admin, flow, assigned, "review", "multi-first");
        assertEquals(2, first.path("instance").path("currentStepNo").asInt());
        assertEquals(username, node(first, "review").path("assigneeNames").get(0).asString());

        var other = data(getJson(flow, reviewer));
        var advanced = submit(reviewer, flow, other, "review", "multi-second");
        assertEquals(3, advanced.path("instance").path("currentStepNo").asInt());
        var recalled = data(postJson(flow + "/actions/recall", reviewer,
                map("requestKey", "multi-recall", "version", version(advanced))));
        var pending = node(recalled, "review").path("assigneeNames");
        assertEquals(1, pending.size());
        assertEquals(username, pending.get(0).asString());
        var returned = data(postJson(flow + "/actions/reject", reviewer, map("requestKey", "multi-reject",
                "version", version(recalled), "todoId", task(recalled, "review"), "reason", "Recheck")));
        assertEquals(1, returned.path("instance").path("currentStepNo").asInt());

        jdbc.update("UPDATE role SET enable = 0 WHERE code IN ('WF_REVIEW_SECOND', 'WF_DEMO_SECURITY')");
        var returnedToAdmin = data(getJson(flow, admin));
        var blocked = body(postJson(flow + "/actions/submit", admin, map("requestKey", "multi-blocked",
                "version", version(returnedToAdmin), "todoId", task(returnedToAdmin, "request"))), 400);
        String message = blocked.path("message").asString();
        assertTrue(message.contains("Second Review"));
        assertTrue(message.contains("流程示例 · 安全"));
    }

    @Test
    void rejectsStartAndSubmitIfPrimaryHandlersAreNoLongerAvailable() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        long definition = publish(admin, "WF_DEMO_PAYMENT");
        var started = start(admin, definition, "before-removal");
        String flow = path(started);
        var history = data(getJson(flow + "/history", admin)).path("total").asInt();
        int inactive = createMember(admin, "inactive_primary");
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) SELECT ?, id FROM role WHERE code='WF_DEMO_CASHIER'", inactive);
        jdbc.update("DELETE FROM wf_role_handler WHERE roleId IN (SELECT id FROM role WHERE code IN ('WF_DEMO_FINANCE', 'WF_DEMO_CASHIER'))");
        jdbc.update("INSERT INTO wf_role_handler (userId, roleId, kind, primaryRoleId) SELECT ?,id,'PRIMARY',id FROM role WHERE code='WF_DEMO_CASHIER'", inactive);
        jdbc.update("UPDATE `user` SET enable = FALSE WHERE id = ?", inactive);

        var starting = map("requestKey", "after-removal", "title", "Approval", "definitionId", definition);
        var rejectedStart = body(postJson("/workflow/instances", admin, starting), 400).path("data");
        assertEquals(1, data(getJson("/workflow/instances", admin, "scope", "STARTED")).path("total").asInt());
        var submitting = map("requestKey", "submit-after-removal", "version", version(started), "todoId", task(started, "request"),
                "fields", map("wf_demo_subject", "Payment", "wf_demo_amount", "12.00"));
        var rejectedSubmit = body(postJson(flow + "/actions/submit", admin, submitting), 400).path("data");
        assertEquals(rejectedStart, rejectedSubmit);
        assertEquals(List.of("finance", "cashier"), rejectedSubmit.path("missingPrimaryHandlers").valueStream()
                .map(issue -> issue.path("nodeKey").asString()).toList());
        var current = data(getJson(flow, admin));
        assertEquals(version(started), version(current));
        assertEquals(task(started, "request"), task(current, "request"));
        assertEquals(history, data(getJson(flow + "/history", admin)).path("total").asInt());

        jdbc.update("UPDATE `user` SET enable = TRUE WHERE id = ?", inactive);
        jdbc.update("INSERT INTO wf_role_handler (userId, roleId, kind, primaryRoleId) SELECT 1,id,'PRIMARY',id FROM role WHERE code='WF_DEMO_FINANCE'");
        var advanced = data(postJson(flow + "/actions/submit", admin, submitting));
        assertEquals(2, advanced.path("instance").path("currentStepNo").asInt());
        assertTrue(task(advanced, "finance") > 0);
    }

    @Test
    void countersignWithoutMembersCanStillWaitAndBeReassigned() throws Exception {
        String admin = loginAsAdmin();
        installMembers();
        var started = start(admin, publish(admin, "WF_DEMO_ACCESS"), "WAIT");
        jdbc.update("DELETE FROM user_roles_role WHERE roleId IN (SELECT id FROM role WHERE code='WF_DEMO_SECURITY')");
        var waiting = data(postJson(path(started) + "/actions/submit", admin, map("requestKey", "submit",
                "version", version(started), "todoId", task(started, "request"),
                "fields", map("wf_demo_subject", "Permit", "wf_demo_description", "Contractor"))));
        assertTrue(waiting.path("canRetry").asBoolean());
        assertEquals("WAITING_ASSIGNMENT", node(waiting, "security").path("status").asString());
        jdbc.update("INSERT INTO user_roles_role(userId, roleId) SELECT 1, id FROM role WHERE code='WF_DEMO_SECURITY'");
        var assigned = data(postJson(path(started) + "/actions/retry-assignment", admin,
                map("requestKey", "assign", "version", version(waiting))));
        assertEquals("ACTIVE", node(assigned, "security").path("status").asString());
        assertTrue(task(assigned, "security") > 0);
    }

    private int createMember(String admin, String user) throws Exception {
        data(postJson("/user", admin, map("username", user, "roleIds", List.of(2))));
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username = ?", Integer.class, user);
    }

    private void installMembers() {
        jdbc.update("INSERT INTO user_roles_role (userId, roleId) SELECT 1,id FROM role WHERE code LIKE 'WF_DEMO_%'");
        jdbc.update("INSERT INTO wf_role_handler (userId, roleId, kind, primaryRoleId) SELECT 1,id,'PRIMARY',id FROM role WHERE code LIKE 'WF_DEMO_%'");
    }

    private long publish(String token, String code) throws Exception {
        var draft = data(getJson("/workflow/definitions", token)).path("pageData").valueStream()
                .filter(item -> item.path("code").asString().equals(code)).findFirst().orElseThrow();
        long id = draft.path("id").asLong();
        data(postJson("/workflow/definitions/" + id + "/publish", token, map("version", draft.path("version").asLong())));
        return id;
    }

    private JsonNode start(String token, long definition, String key) throws Exception {
        return data(postJson("/workflow/instances", token, map("requestKey", key, "title", "Approval", "definitionId", definition)));
    }

    private JsonNode submit(String token, String flow, JsonNode detail, String node, String key) throws Exception {
        return data(postJson(flow + "/actions/submit", token, map("requestKey", key, "version", version(detail),
                "todoId", task(detail, node))));
    }

    private static String path(JsonNode detail) {
        return "/workflow/instances/" + detail.path("instance").path("id").asLong();
    }

    private static long version(JsonNode detail) {
        return detail.path("instance").path("version").asLong();
    }

    private static JsonNode node(JsonNode detail, String key) {
        return detail.path("nodes").valueStream().filter(item -> item.path("nodeKey").asString().equals(key))
                .reduce((previous, latest) -> latest).orElseThrow();
    }

    private static long task(JsonNode detail, String node) {
        return detail.path("tasks").valueStream().filter(item -> item.path("canSubmit").asBoolean()
                && item.path("nodeKey").asString().equals(node)).findFirst().orElseThrow().path("todoId").asLong();
    }
}
