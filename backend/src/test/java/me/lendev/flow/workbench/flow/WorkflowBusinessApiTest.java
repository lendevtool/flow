package me.lendev.flow.workbench.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import me.lendev.flow.support.ApiTestBase;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.EventType;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql("/db/seed/workflow-examples.sql")
@Import(WorkflowBusinessApiTest.Modules.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_business;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "workflow.business.delivery.poll-ms=600000"
})
class WorkflowBusinessApiTest extends ApiTestBase {

    private static final String ORDER = "/workflow/business/purchase/PURCHASE_ORDER/PO-01/instances";
    private static final String TEMPLATE = "BUSINESS_APPROVAL";
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private WorkflowBusinessService service;
    @Autowired
    private WorkflowBusinessDeliveryWorker worker;
    @Autowired
    private WorkflowBusinessDelivery delivery;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private Adapter purchase;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM wf_business_delivery");
        purchase.received.clear();
        purchase.fail = false;
        purchase.allowReceivers = true;
        purchase.allowStart = true;
    }

    @Test
    void startsAndReplaysAcrossRevisionsAndAuthorizesSourceCancellation() throws Exception {
        String token = loginAsAdmin();
        installRoles();
        long revision1 = publish(token, TEMPLATE);
        var input = map("requestKey", "stable", "templateCode", TEMPLATE, "title", "Order", "fields",
                map("wf_demo_subject", "Initial"));
        var first = data(postJson(ORDER, token, input));
        assertEquals(revision1, first.path("definitionId").asLong());
        assertEquals("Initial", data(getJson(instance(first), token)).path("values").path("wf_demo_subject").asString());
        assertEquals("/purchases/PO-01", data(getJson(instance(first), token)).path("sourceUrl").asString());
        assertEquals(1L, data(getJson(ORDER, token)).path("total").asLong());

        assertNotEquals(revision1, publish(token, TEMPLATE));
        purchase.allowStart = false;
        assertEquals(first, data(postJson(ORDER, token, input)));
        purchase.allowStart = true;
        var otherModule = data(postJson(ORDER.replace("/purchase/", "/warehouse/"), token, input));
        assertNotEquals(first.path("flowInstanceId"), otherModule.path("flowInstanceId"));
        input.put("title", "Different");
        body(postJson(ORDER, token, input), 409);

        String id = first.path("flowInstanceId").asString();
        var cancel = map("requestKey", "cancel", "version", first.path("version").asLong(), "reason", "Withdraw order");
        body(postJson(ORDER.replace("PO-01", "PO-02") + "/" + id + "/cancel", token, cancel), 404);
        var cancelled = data(postJson(ORDER + "/" + id + "/cancel", token, cancel));
        assertEquals("CANCELLED", cancelled.path("status").asString());
        assertEquals(cancelled, data(postJson(ORDER + "/" + id + "/cancel", token, cancel)));
        assertEquals(1, countDeliveries(id));
        body(getJson(ORDER.replace("PO-01", "PRIVATE"), token), 404);
        body(postJson(ORDER.replace("/purchase/", "/unknown/"), token, input), 400);

        var unauthorizedField = map("requestKey", "unknown-field", "templateCode", TEMPLATE, "title", "Order",
                "fields", map("wf_demo_description", "Hidden"));
        body(postJson(ORDER, token, unauthorizedField), 400);
        var badType = map("requestKey", "bad-type", "templateCode", TEMPLATE, "title", "Order",
                "fields", map("wf_demo_subject", 100));
        body(postJson(ORDER, token, badType), 400);
    }

    @Test
    void rollsBackBusinessStartAndRetriesInOrderWithTheSameEventPayload() throws Exception {
        String token = loginAsAdmin();
        installRoles();
        publish(token, "DELIVERY_ORDER");
        var source = new Source("purchase", "PURCHASE_ORDER", "PO-02", 1);
        var template = new TransactionTemplate(transactions);
        record RolledBack(long instanceId, long messageId) {
        }
        var rolledBack = template.execute(status -> {
            var run = service.startFromBusiness(source,
                    new WorkflowBusinessService.Start("rollback", "DELIVERY_ORDER", null, "Rolled back", null, null));
            long message = jdbcClient.sql("""
                    SELECT m.id FROM sys_in_app_message m
                    JOIN wf_node_task t ON t.id = m.target_id
                    JOIN wf_node_instance n ON n.id = t.nodeInstanceId
                    WHERE m.target_type = 'WORKFLOW_TASK' AND n.flowInstanceId = ?
                    """).param(run.flowInstanceId()).query(Long.class).single();
            status.setRollbackOnly();
            return new RolledBack(run.flowInstanceId(), message);
        });
        assertNotNull(rolledBack);
        assertEquals(0, countDeliveries(Long.toString(rolledBack.instanceId())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_in_app_message WHERE id = ?", Integer.class,
                rolledBack.messageId()));
        assertEquals(0L, service.findBySource(source, 1, 1, 20).getTotal());

        purchase.allowReceivers = false;
        var started = data(postJson(ORDER.replace("PO-01", "PO-02"), token,
                map("requestKey", "deliver", "templateCode", "DELIVERY_ORDER", "title", "Event delivery", "fields",
                        map("wf_demo_subject", "Before", "wf_demo_decision", "Internal"))));
        String flow = instance(started);
        String id = started.path("flowInstanceId").asString();
        assertTrue(data(getJson(flow, token)).path("tasks").isEmpty());
        purchase.allowReceivers = true;
        var assigned = data(postJson(flow + "/actions/retry-assignment", token, map("requestKey", "assign", "version", 0)));
        var submitted = submit(token, flow, assigned, "submit", "After");
        assertEquals("DONE", submitted.path("instance").path("status").asString());
        assertEquals(2, countDeliveries(id));

        purchase.fail = true;
        worker.poll();
        assertEquals(List.of(EventType.WAITING_ASSIGNMENT), purchase.received.stream().map(Event::eventType).toList());
        var first = purchase.received.getFirst();
        assertEquals("Before", first.fields().get("wf_demo_subject").asString());
        assertFalse(first.fields().containsKey("wf_demo_decision"));
        worker.poll();
        assertEquals(1, purchase.received.size());

        jdbc.update("UPDATE wf_business_delivery SET nextAttemptAt = '2000-01-01 00:00:00' WHERE eventId = ?", first.eventId());
        purchase.fail = false;
        worker.poll();
        assertEquals(first, purchase.received.get(1));
        worker.poll();
        var completed = purchase.received.get(2);
        assertEquals(EventType.COMPLETED, completed.eventType());
        assertEquals("After", completed.fields().get("wf_demo_subject").asString());
        worker.poll();
        assertEquals(3, purchase.received.size());
    }

    @Test
    void recoversExpiredLeaseAndAllowsManualRetryAfterExhaustion() throws Exception {
        String token = loginAsAdmin();
        installRoles();
        publish(token, "LEASE_ORDER");
        purchase.allowReceivers = false;
        var started = data(postJson(ORDER.replace("PO-01", "PO-03"), token, map("requestKey", "lease", "templateCode", "LEASE_ORDER", "title", "Lease")));
        long event = delivery.eligible().getFirst();
        var abandoned = delivery.claim(event);
        assertNull(delivery.claim(event));
        jdbc.update("UPDATE wf_business_delivery SET leaseExpiresAt = '2000-01-01 00:00:00' WHERE eventId = ?", event);
        var replacement = delivery.claim(event);
        assertNotEquals(abandoned.token(), replacement.token());
        delivery.acknowledge(abandoned, true, null);
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM wf_business_delivery WHERE eventId=?", String.class, event));
        jdbc.update("UPDATE wf_business_delivery SET attemptCount = ? WHERE eventId = ?", WorkflowBusinessDelivery.MAX_ATTEMPTS, event);
        delivery.acknowledge(replacement, false, "DELIVERY_FAILED");
        var failures = data(getJson("/workflow/business-deliveries", token, "status", "FAILED"));
        assertEquals(1, failures.path("total").asInt());
        data(postJson("/workflow/business-deliveries/" + event + "/retry", token, map()));
        worker.poll();
        assertEquals(Long.toString(event), purchase.received.getFirst().eventId());
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM wf_business_delivery WHERE eventId=?", String.class, event));
        assertEquals(started.path("flowInstanceId").asString(), purchase.received.getFirst().flowInstanceId());
    }

    private int countDeliveries(String flow) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wf_business_delivery d JOIN wf_flow_event e ON e.id=d.eventId WHERE e.flowInstanceId=?",
                Integer.class, flow);
    }

    private void installRoles() {
        jdbc.update("""
                INSERT INTO user_roles_role(userId, roleId)
                SELECT 1,id FROM role WHERE code='WF_DEMO_REQUESTER'
                AND NOT EXISTS (SELECT 1 FROM user_roles_role u WHERE u.roleId=role.id AND u.userId=1)
                """);
        jdbc.update("""
                INSERT INTO wf_role_handler (userId,roleId,kind,primaryRoleId)
                SELECT 1,id,'PRIMARY',id FROM role WHERE code='WF_DEMO_REQUESTER'
                AND NOT EXISTS (SELECT 1 FROM wf_role_handler h WHERE h.roleId=role.id AND h.userId=1)
                """);
    }

    private long publish(String token, String code) throws Exception {
        var template = data(postJson("/workflow/definitions", token, map("code", code, "name", code,
                "fields", List.of("wf_demo_subject", "wf_demo_decision"), "nodes", List.of(map(
                        "key", "review", "order", 1, "title", "Review", "roleCode", "WF_DEMO_REQUESTER", "mode", "PRIMARY")))));
        long id = template.path("id").asLong();
        data(postJson("/workflow/definitions/" + id + "/publish", token, map("version", template.path("version").asLong())));
        return id;
    }

    private JsonNode submit(String token, String flow, JsonNode detail, String key, String value) throws Exception {
        return data(postJson(flow + "/actions/submit", token, map("requestKey", key,
                "version", detail.path("instance").path("version").asLong(),
                "todoId", detail.path("tasks").get(0).path("todoId").asLong(), "fields", map("wf_demo_subject", value))));
    }

    private static String instance(JsonNode summary) {
        return "/workflow/instances/" + summary.path("flowInstanceId").asString();
    }

    @TestConfiguration
    static class Modules {

        @Bean
        Adapter purchase() {
            return new Adapter("purchase");
        }

        @Bean
        WorkflowBusinessSource warehouse() {
            return new Adapter("warehouse");
        }
    }

    static class Adapter implements WorkflowBusinessSource {

        final String module;
        final List<Event> received = new ArrayList<>();
        boolean fail;
        boolean allowReceivers = true;
        boolean allowStart = true;

        Adapter(String module) {
            this.module = module;
        }

        @Override
        public String module() {
            return module;
        }

        @Override
        public boolean mayStart(Source source, String code) {
            return allowStart && mayRead(source, source.ownerId() == null ? 1 : source.ownerId());
        }

        @Override
        public boolean mayRead(Source source, int viewer) {
            return source.type().equals("PURCHASE_ORDER") && source.id().startsWith("PO-") && viewer == 1;
        }

        @Override
        public boolean mayCreate(Source source, int assignee) {
            return allowReceivers && assignee == 1;
        }

        @Override
        public boolean mayCancel(Source source, int actor) {
            return mayRead(source, actor);
        }

        @Override
        public String openPath(Source source, int viewer) {
            return mayRead(source, viewer) ? "/purchases/" + source.id() : null;
        }

        @Override
        public Set<String> inputFields(Source source, String code) {
            return Set.of("wf_demo_subject", "wf_demo_decision");
        }

        @Override
        public Set<String> outputFields(Source source, String code, EventType type) {
            return Set.of("wf_demo_subject");
        }

        @Override
        public Set<EventType> subscriptions() {
            return Set.of(EventType.WAITING_ASSIGNMENT, EventType.COMPLETED, EventType.CANCELLED);
        }

        @Override
        public void onEvent(Event event) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            received.add(event);
            if (fail) {
                throw new IllegalStateException("Simulated failure");
            }
        }
    }
}
