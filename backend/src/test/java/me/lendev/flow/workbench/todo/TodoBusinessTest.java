package me.lendev.flow.workbench.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.FlowModels.Start;
import me.lendev.flow.workbench.flow.FlowService;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import me.lendev.flow.workbench.todo.TodoModels.Write;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:todo_business;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@Import(JdbcFixtureConfiguration.class)
@ActiveProfiles({"h2", "test"})
class TodoBusinessTest {

    private static final Source SOURCE = new Source("fixture", "ORDER", "123", null);

    @Autowired
    private TodoService todos;
    @Autowired
    private FlowService flows;
    @Autowired
    private Caller caller;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void backgroundBusinessCreatesOneAuditedTodoAcrossRetries() {
        Write request = request("standalone");
        long id = todos.createFromBusiness(SOURCE, request).id();
        assertEquals(id, todos.createFromBusiness(SOURCE, request).id());
        assertEquals(1, matchingAudits(id));
    }

    @Test
    void auditedCallerJoinsTodoWriteAndRollsItBackOnFailure() {
        try (var actor = AuditSystemContext.open("业务测试")) {
            long id = caller.create("joined", false).after().id();
            assertEquals(1, matchingAudits(id));
            assertThrows(IllegalStateException.class, () -> caller.create("rolled-back", true));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wb_todo WHERE requestKey='rolled-back'", Integer.class));
    }

    @Test
    void businessFlowNormalizesItsSourceAndReplaysTheSameStart() {
        long definition = publishedDefinition();
        var input = new Start("business-flow", definition, "Confirm order", Map.of(), Map.of());
        var untrimmed = new Source("fixture", " ORDER ", " 123 ", null);
        var started = flows.startFromBusiness(untrimmed, input);
        assertEquals(RunStatus.RUNNING, started.status());
        assertEquals(started, flows.startFromBusiness(SOURCE, input));
        assertEquals("123", jdbcClient.sql("SELECT businessId FROM wf_instance WHERE id = ?")
                .param(started.id()).query(String.class).single());
    }

    @Test
    void businessEntryPointsRejectUnregisteredSourcesAndUnauthorizedAssignees() {
        long definition = publishedDefinition();
        var start = new Start("rejected-flow", definition, "Confirm order", Map.of(), Map.of());
        var invalid = new Start("", definition, "Confirm order", Map.of(), Map.of());
        assertThrows(BadRequestException.class, () -> flows.startFromBusiness(SOURCE, invalid));
        var denied = List.of(new Source("workflow", "ORDER", "123", null),
                new Source("unregistered", "ORDER", "123", null),
                new Source("fixture", "ORDER", "different", null));
        var write = request("rejected-todo");
        for (var source : denied) {
            assertThrows(BadRequestException.class, () -> flows.startFromBusiness(source, start));
            assertThrows(BadRequestException.class, () -> todos.createFromBusiness(source, write));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance WHERE requestKey = 'rejected-flow'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wb_todo WHERE requestKey = 'rejected-todo'",
                Integer.class));
    }

    private long publishedDefinition() {
        String code = "BUSINESS_" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wf_definition (code, revision, name, status, stepsJson, createdBy)
                VALUES (?, 1, 'Business review', 'PUBLISHED',
                    '[{"stepNo":1,"title":"Review","assigneeUserId":1}]', 1)
                """, code);
        return jdbc.queryForObject("SELECT id FROM wf_definition WHERE code = ?", Long.class, code);
    }

    private int matchingAudits(long id) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                WHERE operationCode='workbench.todo.create' AND result='SUCCESS'
                AND payload LIKE ?
                """, Integer.class, "%\"objectId\":\"" + id + "\"%");
    }

    private static Write request(String key) {
        return new Write(key, "业务待办", null, 1, "NORMAL", "FIXED", null, null, LocalDate.of(2099, 12, 31),
                null, null, null, null, null, Map.of(), null, null);
    }

    @TestConfiguration
    static class Config {

        @Bean
        TodoBusinessSource fixtureSource() {
            return new TodoBusinessSource() {
                @Override
                public String module() {
                    return "fixture";
                }

                @Override
                public boolean mayCreate(Source source, int assigneeUserId) {
                    return SOURCE.equals(source) && assigneeUserId == 1;
                }
            };
        }

        @Bean
        Caller caller(TodoService todos) {
            return new Caller(todos);
        }
    }

    static class Caller {

        private final TodoService todos;

        Caller(TodoService todos) {
            this.todos = todos;
        }

        @Audited(AuditOperation.TODO_CREATE)
        @Transactional(rollbackFor = Exception.class)
        public TodoMutations.Change create(String key, boolean fail) {
            var todo = todos.createFromBusiness(SOURCE, request(key));
            if (fail) {
                throw new IllegalStateException("fixture rollback");
            }
            return new TodoMutations.Change(null, todo, false);
        }
    }
}
