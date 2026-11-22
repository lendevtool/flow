package me.lendev.flow.pms.notificationgroup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.DepartmentSelection;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:notification-snapshot-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationReadSnapshotTest extends ApiTestBase {

    @Autowired
    NotificationReadSnapshot snapshots;
    @Autowired
    NotificationGroupStore store;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void membershipCommittedAfterSceneReadIsOnlyVisibleInTheNextSnapshot() {
        String code = "notify.snapshot";
        String departmentName = "通知快照测试部门";
        jdbc.update("INSERT INTO sys_notification_scene(scene_code,name_cn,updated_at) VALUES(?,?,CURRENT_TIMESTAMP)",
                code, "通知快照测试");
        jdbc.update("INSERT INTO sys_department(name) VALUES(?)", departmentName);
        long department = jdbc.queryForObject("SELECT id FROM sys_department WHERE name=?", Long.class, departmentName);
        List<DepartmentSelection> selections = List.of(new DepartmentSelection(department, false));
        try (var writer = Executors.newSingleThreadExecutor()) {
            List<Long> withinSnapshot = snapshots.read(() -> {
                assertEquals(code, store.scene(code, false).sceneCode());
                var committed = writer.submit(() -> jdbc.update(
                        "INSERT INTO sys_department_member(user_id,department_id) VALUES(1,?)", department));
                assertEquals(1, assertDoesNotThrow(() -> committed.get(5, TimeUnit.SECONDS)));
                return store.recipientIds(snapshots.recipients(List.of(), selections), 10);
            });

            assertEquals(List.of(), withinSnapshot);
            List<Long> nextSnapshot = snapshots.read(
                    () -> store.recipientIds(snapshots.recipients(List.of(), selections), 10));
            assertEquals(List.of(1L), nextSnapshot);
        } finally {
            jdbc.update("DELETE FROM sys_department_member WHERE department_id=?", department);
            jdbc.update("DELETE FROM sys_department WHERE id=?", department);
            jdbc.update("DELETE FROM sys_notification_scene WHERE scene_code=?", code);
        }
    }
}
