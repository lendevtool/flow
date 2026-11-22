package me.lendev.flow.workbench.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import me.lendev.flow.workbench.todo.TodoModels.Todo;
import me.lendev.flow.workbench.todo.TodoModels.Write;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:todo_business_association;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@Import({JdbcFixtureConfiguration.class, TodoBusinessAssociationTest.Config.class})
@ActiveProfiles({"h2", "test"})
class TodoBusinessAssociationTest {

    private static final Source SOURCE = new Source("association-fixture", "ORDER", "Case", null);
    private static final BusinessTodoRef ORDER = new BusinessTodoRef("association-fixture", "ORDER", "Case");
    private static final BusinessTodoRef SUPPLIER = new BusinessTodoRef("catalog", "SUPPLIER", "A'17");
    private static final BusinessTodoRef CASE_VARIANT = new BusinessTodoRef("catalog", "SUPPLIER", "a'17");
    private static final BusinessTodoRef SUPPLEMENTARY = new BusinessTodoRef("catalog", "SUPPLIER", "😃".repeat(128));
    private static final String KIND = "REVIEW";

    @Autowired
    private TodoService todos;
    @Autowired
    private TodoBusinessAssociationService associations;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void normalizesAndDeduplicatesLinksWithCaseSensitiveIds() {
        long id = todo(request());
        var padded = new BusinessTodoRef("catalog", "SUPPLIER", "  A'17  ");
        var result = transactions.execute(status ->
                associations.associate(id, ORDER, KIND, List.of(ORDER, SUPPLIER, padded, CASE_VARIANT)));
        assertNotNull(result);
        assertEquals(Set.of(SUPPLIER, CASE_VARIANT), result.references());
        assertEquals(2L, countLinks(id));
        assertEquals(2L, jdbc.sql("""
                SELECT COUNT(*) FROM wb_todo_business_link link
                JOIN wb_todo_business_context context ON context.todoId = link.todoId
                    AND context.createdAt = link.createdAt
                WHERE link.todoId = ?
                """).param(id).query(Long.class).single());

        var replay = transactions.execute(status ->
                associations.associate(id, ORDER, KIND, List.of(CASE_VARIANT, SUPPLIER)));
        assertEquals(result, replay);
    }

    @Test
    void supportsEmptyLinkSetAndRejectsChangesOnReplay() {
        long id = todo(request());
        transactions.executeWithoutResult(status -> associations.associate(id, ORDER, KIND, List.of()));
        assertEquals(0L, countLinks(id));
        assertThrows(TodoConflictException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, ORDER, KIND, List.of(SUPPLIER))));
        assertThrows(TodoConflictException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, ORDER, "APPROVE", List.of())));
        assertEquals(0L, countLinks(id));
    }

    @Test
    void acceptsUpTo128UnicodeCodePointsInBusinessObjectIds() {
        long id = todo(request());
        var result = transactions.execute(status -> associations.associate(id, ORDER, KIND, List.of(SUPPLEMENTARY)));
        assertNotNull(result);
        assertEquals(Set.of(SUPPLEMENTARY), result.references());
        assertEquals(result, transactions.execute(status -> associations.associate(id, ORDER, KIND, List.of(SUPPLEMENTARY))));
        var tooLong = new BusinessTodoRef("catalog", "SUPPLIER", "😃".repeat(129));
        assertThrows(BadRequestException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, ORDER, KIND, List.of(tooLong))));
    }

    @Test
    void rejectsUnprovenOwnerOrLink() {
        long id = todo(request());
        var wrongOrder = new BusinessTodoRef("association-fixture", "ORDER", "case");
        var unproven = new BusinessTodoRef("catalog", "SUPPLIER", "unknown");
        var unregistered = new BusinessTodoRef("unknown", "ORDER", "Case");
        assertThrows(BadRequestException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, wrongOrder, KIND, List.of())));
        assertThrows(BadRequestException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, ORDER, KIND, List.of(unproven))));
        assertThrows(BadRequestException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, unregistered, KIND, List.of())));
        assertEquals(0L, countContexts(id));
    }

    @Test
    void rejectsMalformedReferencesAndRequiresOuterTransaction() {
        long id = todo(request());
        assertThrows(IllegalTransactionStateException.class, () ->
                associations.associate(id, ORDER, KIND, List.of()));
        var control = new BusinessTodoRef("catalog", "SUPPLIER", "A\u000b17");
        var tooLong = new BusinessTodoRef("catalog", "SUPPLIER", "X".repeat(129));
        var badType = new BusinessTodoRef("catalog", "supplier", "A'17");
        var badModule = new BusinessTodoRef("Catalog", "SUPPLIER", "A'17");
        for (var invalid : List.of(control, tooLong, badType, badModule)) {
            assertThrows(BadRequestException.class, () -> transactions.executeWithoutResult(status ->
                    associations.associate(id, ORDER, KIND, List.of(invalid))));
        }
        assertThrows(BadRequestException.class, () -> transactions.executeWithoutResult(status ->
                associations.associate(id, ORDER, "review", List.of())));
        assertEquals(0L, countContexts(id));
    }

    @Test
    void rollsBackTodoAndRelationsTogether() {
        var request = request();
        long id = transactions.execute(status -> {
            long created = todo(request);
            associations.associate(created, ORDER, KIND, List.of(SUPPLIER));
            status.setRollbackOnly();
            return created;
        });
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM wb_todo WHERE id = ?").param(id).query(Long.class).single());
        assertEquals(0L, countContexts(id));
        assertEquals(0L, countLinks(id));
    }

    @Test
    void parentDeletionCascadesThroughBothTables() {
        long id = todo(request());
        transactions.executeWithoutResult(status -> associations.associate(id, ORDER, KIND, List.of(SUPPLIER)));
        assertEquals(1, jdbc.sql("DELETE FROM wb_todo WHERE id = ?").param(id).update());
        assertEquals(0L, countContexts(id));
        assertEquals(0L, countLinks(id));
    }

    @Test
    void concurrentReplaysSaveOneContextAndLink() throws Exception {
        long id = todo(request());
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> replayTogether(id, ready));
            var second = executor.submit(() -> replayTogether(id, ready));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, countContexts(id));
        assertEquals(1L, countLinks(id));
    }

    private TodoBusinessAssociationService.Association replayTogether(long id, CyclicBarrier ready) throws Exception {
        ready.await(10, TimeUnit.SECONDS);
        return transactions.execute(status -> associations.associate(id, ORDER, KIND, List.of(SUPPLIER)));
    }

    private long countContexts(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM wb_todo_business_context WHERE todoId = ?")
                .param(id).query(Long.class).single();
    }

    private long countLinks(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM wb_todo_business_link WHERE todoId = ?")
                .param(id).query(Long.class).single();
    }

    private long todo(Write request) {
        return todos.createFromBusiness(SOURCE, request).id();
    }

    private static Write request() {
        return new Write(UUID.randomUUID().toString(), "业务关联测试", null, 1, "NORMAL", "FIXED", null, null,
                LocalDate.of(2099, 12, 31), null, null, null, null, null, Map.of(), null, null);
    }

    @TestConfiguration
    static class Config {

        @Bean
        TodoBusinessSource associationSource() {
            return new TodoBusinessSource() {
                @Override
                public String module() {
                    return SOURCE.module();
                }

                @Override
                public boolean mayCreate(Source source, int assigneeUserId) {
                    return SOURCE.equals(source) && assigneeUserId == 1;
                }
            };
        }

        @Bean
        TodoBusinessAssociationPolicy associationPolicy() {
            return new TodoBusinessAssociationPolicy() {
                @Override
                public String recordModule() {
                    return ORDER.module();
                }

                @Override
                public String recordType() {
                    return ORDER.objectType();
                }

                @Override
                public boolean allows(Todo todo, BusinessTodoRef businessRecord, String taskKindCode,
                                      Set<BusinessTodoRef> references) {
                    return SOURCE.module().equals(todo.sourceModule()) && SOURCE.type().equals(todo.businessType())
                            && SOURCE.id().equals(todo.businessId()) && ORDER.equals(businessRecord)
                            && Set.of(SUPPLIER, CASE_VARIANT, SUPPLEMENTARY).containsAll(references);
                }
            };
        }
    }
}
