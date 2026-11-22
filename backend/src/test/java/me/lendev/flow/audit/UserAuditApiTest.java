package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;

import java.util.*;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

class UserAuditApiTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditWorker worker;

    long create(String token, String name, List<Integer> roles) throws Exception {
        var created = data(postJson("/user", token, map("username", name, "enable", true,
                "roleIds", roles, "profile", map("nickName", "测试用户", "email", "full@example.test"))));
        String temporary = created.path("temporaryPassword").asString();
        assertFalse(latest("system.user.create").toString().contains(temporary));
        String restricted = login(name, temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", "OldAudit1234!")));
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, name);
    }

    JsonNode latest(String code) {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationCode=? ORDER BY id DESC LIMIT 1", String.class, code));
    }

    void clean(long id) {
        jdbc.update("DELETE FROM user_roles_role WHERE userId=?", id);
        jdbc.update("DELETE FROM profile WHERE userId=?", id);
        jdbc.update("DELETE FROM `user` WHERE id=?", id);
    }

    @Test
    void createUpdateAndRoleReplacementCaptureFullBusinessDataWithoutCredentials() throws Exception {
        String token = loginAsAdmin();
        long id = create(token, "audit_user", List.of(2));
        var created = latest("system.user.create");
        var snapshot = created.path("objects").get(0).path("afterData");
        assertEquals("audit_user", snapshot.path("username").path("value").asString());
        assertEquals("full@example.test", snapshot.path("profile.email").path("value").asString());
        String hash = jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id);
        assertFalse(created.toString().contains("OldAudit1234!"));
        assertFalse(created.toString().contains(hash));
        assertFalse(created.toString().contains("identityKey"));
        data(patchJson("/user/" + id, token, map("roleIds", List.of(2, 1, 2))));
        var changed = latest("system.user.update").path("objects").get(0);
        assertEquals(List.of("1", "2"), objectMapper.convertValue(changed.path("afterData").path("roleIds").path("value"), List.class));
        data(postJson("/user/roles/add/" + id, token, map("roleIds", List.of(1, 2))));
        assertEquals("NO_CHANGE", latest("system.user.role.replace").path("result").asString());
        data(postJson("/user/roles/add/" + id, token, map("roleIds", List.of())));
        assertTrue(latest("system.user.role.replace").path("objects").get(0).path("afterData").path("roleIds").path("value").isEmpty());
    }

    @Test
    void profileUpdateFreezesBeforeActorAndRecordsActualProfile() throws Exception {
        String token = loginAsAdmin();
        data(patchJson("/user/profile/1", token, map("nickName", "新昵称", "email", "new@example.test")));
        var event = latest("system.user.profile.update");
        assertEquals("Admin", event.path("actor").path("name").asString());
        var change = event.path("objects").get(0);
        assertEquals("Admin", change.path("beforeData").path("profile.nickName").path("value").asString());
        assertEquals("新昵称", change.path("afterData").path("profile.nickName").path("value").asString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void passwordResetAndSelfChangeHaveReferencesAndCorrelatedSessionFacts() throws Exception {
        String admin = loginAsAdmin();
        long id = create(admin, "audit_password", List.of(2));
        try {
            String oldToken = login("audit_password", "OldAudit1234!");
            String temporary = data(patchJson("/user/password/reset/" + id, admin, null))
                    .path("temporaryPassword").asString();
            var reset = latest("system.user.password.reset");
            var session = latest("system.user.session.revoke");
            assertEquals("SUCCESS", reset.path("result").asString());
            assertEquals("REFERENCE", reset.path("objects").get(0).path("entryType").asString());
            assertEquals(reset.path("operationId"), session.path("operationId"));
            assertEquals("SUCCESS", session.path("result").asString());
            assertFalse(reset.toString().contains(temporary));
            body(getJson("/user/detail", oldToken), 401);
            String current = login("audit_password", temporary);
            data(postJson("/auth/password", current, map("oldPassword", temporary, "newPassword", "LastAudit123!")));
            var self = latest("system.user.password.change");
            assertEquals(Long.toString(id), self.path("actor").path("id").asString());
            assertEquals("SUCCESS", self.path("result").asString());
            assertFalse(self.toString().contains("LastAudit123!"));
            assertFalse(self.toString().contains(temporary));
            body(getJson("/user/detail", current), 401);
        } finally {
            clean(id);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletionCapturesProfileAndAssignmentsBeforeRemovingAccount() throws Exception {
        String admin = loginAsAdmin();
        long id = create(admin, "audit_delete", List.of(2));
        try {
            data(deleteJson("/user/" + id, admin));
            var event = latest("system.user.delete");
            assertEquals("audit_delete", event.path("objects").get(0).path("beforeData").path("username").path("value").asString());
            assertTrue(event.path("objects").get(0).path("afterData").isNull());
            assertEquals(event.path("operationId"), latest("system.user.session.revoke").path("operationId"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE userId=?", Integer.class, id));
        } finally {
            clean(id);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void selfDisableRetainsOriginalActorForPostCommitSessionCleanup() throws Exception {
        String admin = loginAsAdmin();
        long id = create(admin, "audit_self_disable", List.of(1));
        try {
            String own = login("audit_self_disable", "OldAudit1234!");
            data(patchJson("/user/" + id, own, map("enable", false)));
            var cleanup = latest("system.user.session.revoke");
            assertEquals(Long.toString(id), cleanup.path("actor").path("id").asString());
            assertEquals("SUCCESS", cleanup.path("result").asString());
            assertEquals(latest("system.user.update").path("operationId"), cleanup.path("operationId"));
            body(getJson("/user/detail", own), 401);
        } finally {
            clean(id);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initialRootOrSessionIntentFailureRollsBackWithoutLoggingUserOut() throws Exception {
        String admin = loginAsAdmin();
        long id = create(admin, "audit_user_rollback", List.of(2));
        try {
            String own = login("audit_user_rollback", "OldAudit1234!");
            String identity = jdbc.queryForObject("SELECT identityKey FROM `user` WHERE id=?", String.class, id);
            for (String rejected : List.of("system.user.update", "system.user.session.revoke")) {
                long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM sys_audit_outbox", Long.class);
                jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_user_audit CHECK(id <= " + existing
                        + " OR operationCode <> '" + rejected + "')");
                try {
                    body(patchJson("/user/" + id, admin, map("enable", false)), 503);
                } finally {
                    jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_user_audit");
                }
                assertTrue(jdbc.queryForObject("SELECT enable FROM `user` WHERE id=?", Boolean.class, id));
                assertEquals(identity, jdbc.queryForObject("SELECT identityKey FROM `user` WHERE id=?", String.class, id));
                data(getJson("/user/detail", own));
            }
        } finally {
            clean(id);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void redisCleanupFailureKeepsCommittedDisableAndDoesNotReplayBusiness() throws Exception {
        String admin = loginAsAdmin();
        long id = create(admin, "audit_redis_failure", List.of(2));
        try (var stp = mockStatic(StpUtil.class, CALLS_REAL_METHODS)) {
            stp.when(() -> StpUtil.logout(id)).thenThrow(new IllegalStateException("raw-redis-secret"));
            data(patchJson("/user/" + id, admin, map("enable", false)));
            assertFalse(jdbc.queryForObject("SELECT enable FROM `user` WHERE id=?", Boolean.class, id));
            assertEquals("SUCCESS", latest("system.user.update").path("result").asString());
            var cleanup = latest("system.user.session.revoke");
            assertEquals("UNKNOWN", cleanup.path("result").asString());
            assertFalse(cleanup.toString().contains("raw-redis-secret"));
            worker.runOnce();
            stp.verify(() -> StpUtil.logout(id), times(1));
        } finally {
            clean(id);
        }
    }
}
