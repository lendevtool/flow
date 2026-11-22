package me.lendev.flow.workbench.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(JdbcFixtureConfiguration.class)
@ActiveProfiles({"h2", "test"})
@Transactional
class TodoPublicDimensionsTest {

    @Autowired
    private TodoPublicDimensions dimensions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstReferenceRollsBackWithTheBusinessTransaction() {
        long slotId = slotId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            long itemId = bindDictionary(slotId);
            dimensions.validate(Map.of(1, itemId), Map.of(), false);
            assertEquals("{\"bindingLocked\":true}", slotExtra(slotId));
            status.setRollbackOnly();
        });
        assertNull(slotExtra(slotId));
    }

    @Test
    void existingAndInheritedValuesRemainAvailableAfterDisable() {
        long slotId = slotId();
        long itemId = bindDictionary(slotId);
        Map<Integer, Long> selected = Map.of(1, itemId);

        dimensions.validate(selected, Map.of(), false);
        jdbc.update("UPDATE sys_param_item SET enable = 0 WHERE id = ?", itemId);
        var first = dimensions.list().getFirst();
        assertEquals(itemId, first.options().getFirst().id());
        assertFalse(first.options().getFirst().enabled());
        dimensions.validate(selected, selected, false);
        dimensions.validate(selected, Map.of(), true);
        Map<Integer, Long> noExisting = Map.of();
        assertThrows(BadRequestException.class, () -> dimensions.validate(selected, noExisting, false));
    }

    private long bindDictionary(long slotId) {
        jdbc.update("INSERT INTO sys_param_group (code, name) VALUES ('WORKBENCH_TODO_DICT_CASES', 'Cases')");
        long dictionaryId = jdbc.queryForObject(
                "SELECT id FROM sys_param_group WHERE code = 'WORKBENCH_TODO_DICT_CASES'", Long.class);
        jdbc.update("INSERT INTO sys_param_item (groupId, code, name) VALUES (?, 'INTERNAL', 'Internal')", dictionaryId);
        long itemId = jdbc.queryForObject(
                "SELECT id FROM sys_param_item WHERE groupId = ? AND code = 'INTERNAL'", Long.class, dictionaryId);
        jdbc.update("UPDATE sys_param_item SET value1 = ?, enable = 1 WHERE id = ?", Long.toString(dictionaryId), slotId);
        return itemId;
    }

    private long slotId() {
        return jdbc.queryForObject("""
                SELECT i.id FROM sys_param_item i JOIN sys_param_group g ON i.groupId = g.id
                WHERE g.code = 'WORKBENCH_TODO_DIMENSION' AND i.code = 'DIMENSION_1'
                """, Long.class);
    }

    private String slotExtra(long slotId) {
        return jdbc.queryForObject("SELECT extra FROM sys_param_item WHERE id = ?", String.class, slotId);
    }
}
