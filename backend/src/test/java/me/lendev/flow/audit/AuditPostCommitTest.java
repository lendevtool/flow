package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditPostCommitTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager manager;
    @Autowired
    AuditPostCommit postCommit;
    @Autowired
    AuditWorker worker;
    private TransactionTemplate tx;
    private String operationId;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<AuditObject> targets = List.of(new AuditObject("USER", "23", "tester", "测试用户", null, null));

    @BeforeEach
    void initialize() {
        tx = new TransactionTemplate(manager);
        operationId = UUID.randomUUID().toString();
        var scope = AuditScope.builder().operationId(operationId).startedAt(Instant.now()).startedNanos(System.nanoTime())
                .actor(new AuditActor("USER", "23", "tester", "测试用户", "SUPER_ADMIN", "超级管理员"))
                .sourceType("API").targets(targets).build();
        AuditInvocation.CURRENT.set(new AuditInvocation.State(scope, AuditOperation.USER_DELETE, false));
        jdbc.execute("CREATE TABLE IF NOT EXISTS audit_post_demo(id INTEGER PRIMARY KEY, v VARCHAR(20))");
        jdbc.update("DELETE FROM audit_post_demo");
        jdbc.update("INSERT INTO audit_post_demo VALUES(1,'before')");
    }

    @AfterEach
    void clear() {
        AuditInvocation.CURRENT.remove();
    }

    @Test
    void rollbackRemovesPreparedIntentAndNeverRunsExternalWork() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            postCommit.run(AuditOperation.USER_SESSION_REVOKE, targets, calls::incrementAndGet);
            assertEquals(0, calls.get());
            assertEquals("PREPARED", state());
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE operationId=?", Integer.class, operationId));
    }

    @Test
    void commitRunsOnceWithFrozenActorAndCorrelatedOperationId() {
        tx.executeWithoutResult(status -> postCommit.run(AuditOperation.USER_SESSION_REVOKE, targets, () -> {
            calls.incrementAndGet();
            assertEquals("PREPARED", state());
            return null;
        }));
        assertEquals(1, calls.get());
        assertEquals("PENDING", state());
        var event = objectMapper.readTree(payload());
        assertEquals(operationId, event.path("operationId").asString());
        assertEquals("23", event.path("actor").path("id").asString());
        assertEquals("SUCCESS", event.path("result").asString());
        assertEquals("USER", event.path("objects").get(0).path("object").path("objectType").asString());
    }

    @Test
    void externalFailureDoesNotRollbackCommittedBusinessOrLeakRawMessage() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE audit_post_demo SET v='after' WHERE id=1");
            postCommit.run(AuditOperation.USER_SESSION_REVOKE, targets, () -> {
                throw new IllegalStateException("secret-detail");
            });
        });
        assertEquals("after", jdbc.queryForObject("SELECT v FROM audit_post_demo WHERE id=1", String.class));
        assertEquals("UNKNOWN", objectMapper.readTree(payload()).path("result").asString());
        assertFalse(payload().contains("secret-detail"));
    }

    @Test
    void preparationFailureRollsBackBusinessBeforeAnyExternalWork() {
        long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM sys_audit_outbox", Long.class);
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_post_intent CHECK(id <= " + existing + ")");
        try {
            assertThrows(AuditUnavailableException.class, () -> tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE audit_post_demo SET v='after' WHERE id=1");
                postCommit.run(AuditOperation.USER_SESSION_REVOKE, targets, calls::incrementAndGet);
            }));
            assertEquals("before", jdbc.queryForObject("SELECT v FROM audit_post_demo WHERE id=1", String.class));
            assertEquals(0, calls.get());
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_post_intent");
        }
    }

    @Test
    void confirmationFailureLeavesDurableUnknownRecoveryWithoutReplayingWork() {
        long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM sys_audit_outbox", Long.class);
        jdbc.execute("ALTER TABLE sys_audit_outbox ADD CONSTRAINT reject_post_confirm CHECK(id <= " + existing + " OR status='PREPARED')");
        try {
            tx.executeWithoutResult(status -> postCommit.run(AuditOperation.USER_SESSION_REVOKE, targets, calls::incrementAndGet));
            assertEquals(1, calls.get());
            assertEquals("PREPARED", state());
        } finally {
            jdbc.execute("ALTER TABLE sys_audit_outbox DROP CONSTRAINT reject_post_confirm");
        }
        jdbc.update("UPDATE sys_audit_outbox SET prepareDeadlineAt=? WHERE operationId=?", AuditStore.time(Instant.EPOCH), operationId);
        worker.runOnce();
        assertEquals(1, calls.get());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT result FROM sys_operation_log WHERE operationId=?", String.class, operationId));
    }

    private String state() {
        return jdbc.queryForObject("SELECT status FROM sys_audit_outbox WHERE operationId=?", String.class, operationId);
    }

    private String payload() {
        return jdbc.queryForObject("SELECT payload FROM sys_audit_outbox WHERE operationId=?", String.class, operationId);
    }
}
