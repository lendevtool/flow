package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.query.mapper.AuditQueryMapper;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.audit.store.mapper.AuditStoreMapper;
import me.lendev.flow.support.ApiTestBase;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditOutboxTest extends ApiTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    PlatformTransactionManager manager;
    @Autowired
    AuditStoreMapper mapper;
    @Autowired
    AuditQueryMapper logs;
    @Autowired
    SqlSessionFactory sqlSessions;
    private AuditStore store;
    private AuditWorker worker;
    private AuditProperties properties;
    private MutableClock clock;
    private TransactionTemplate tx;

    @BeforeEach
    void setupAudit() {
        jdbc.update("DELETE FROM sys_operation_change");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM sys_audit_outbox");
        clock = new MutableClock(Instant.parse("2026-11-06T00:00:00Z"));
        properties = new AuditProperties();
        store = new AuditStore(mapper, logs, sqlSessions, objectMapper, manager, properties, clock);
        worker = new AuditWorker(store, properties);
        tx = new TransactionTemplate(manager);
    }

    private AuditEvent event() {
        var snapshots = new AuditSnapshots(objectMapper);
        var ref = new AuditObject("PARAM_ITEM", "12", "home", "首页", "PARAM_GROUP", "4");
        var change = snapshots.created(snapshots.capture(ref,
                Map.of("name", AuditField.visible("名称", "首页"))));
        return AuditEvent.builder().eventId(UUID.randomUUID().toString())
                .operationId(UUID.randomUUID().toString()).schemaVersion(1).phase("DATABASE_COMMIT")
                .startedAt(clock.instant()).occurredAt(clock.instant()).durationMs(0L)
                .actor(AuditActor.system("测试任务")).moduleCode("AUDIT_TEST")
                .operationCode("audit.test.create").action("CREATE").summary("新建参数明细 首页")
                .result("SUCCESS").sourceType("SYSTEM").objects(List.of(change)).build();
    }

    private long enqueue(AuditEvent event) {
        return tx.execute(status -> store.enqueue(event));
    }

    @Test
    void enqueueRequiresTransactionAndRollsBackWithBusiness() {
        var event = event();
        assertThrows(IllegalStateException.class, () -> store.enqueue(event));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jdbc.update("UPDATE sys_param_group SET name=name WHERE id=-1");
            store.enqueue(event);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox", Integer.class));
    }

    @Test
    void dueReturnsOnlyTheOldestBatchWithoutLoadingPayloads() {
        properties.setBatchSize(2);
        long first = enqueue(event());
        long second = enqueue(event());
        clock.advance(1);
        long third = enqueue(event());

        var batch = store.due();
        assertEquals(List.of(first, second), batch.stream().map(AuditStore.Delivery::id).toList());
        batch.forEach(candidate -> assertNull(candidate.payload()));
        assertNotNull(store.find(third).payload());
    }

    @Test
    void consumesSafeEventIntoSummaryAndChangesExactlyOnce() {
        var event = event();
        long id = enqueue(event);
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertEquals(1, store.find(id).attempts());
        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM sys_operation_log WHERE eventId=?")
                .param(event.eventId()).query(Integer.class).single());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_change", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT durationMs FROM sys_operation_log", Long.class));
        assertNull(jdbc.queryForObject("SELECT httpStatus FROM sys_operation_log", Integer.class));
        worker.runOnce();
        assertEquals(1, store.find(id).attempts());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Integer.class));
    }

    @Test
    void onlyOneClaimWinsAndOldLeaseCannotConfirmNewClaim() {
        long id = enqueue(event());
        var candidate = store.find(id);
        var old = store.claim(candidate);
        assertNotNull(old);
        assertNull(store.claim(candidate));
        clock.advance(61);
        store.recoverExpired();
        clock.advance(6);
        var next = store.claim(store.find(id));
        assertNotNull(next);
        assertThrows(IllegalStateException.class, () -> store.consume(old));
        store.consume(next);
        assertEquals("DONE", store.find(id).status());
        assertEquals(2, store.find(id).attempts());
    }

    @Test
    void finalSummaryAndDoneRollBackTogetherThenRetrySucceeds() {
        long id = enqueue(event());
        jdbc.execute("ALTER TABLE sys_operation_change ADD CONSTRAINT test_reject_change CHECK (objectNo < 0)");
        try {
            worker.runOnce();
            assertEquals("RETRY", store.find(id).status());
            assertEquals(1, store.find(id).failureCount());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE sys_operation_change DROP CONSTRAINT test_reject_change");
        }
        clock.advance(6);
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertEquals(0, store.find(id).failureCount());
    }

    @Test
    void identicalDeliveryIsDeduplicatedButConflictingHashIsDead() {
        long id = enqueue(event());
        worker.runOnce();
        jdbc.update("UPDATE sys_audit_outbox SET status='PENDING', processedAt=NULL, nextRetryAt=? WHERE id=?",
                AuditStore.time(clock.instant()), id);
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Integer.class));
        jdbc.update("UPDATE sys_audit_outbox SET status='PENDING', processedAt=NULL, nextRetryAt=?, payloadHash=? WHERE id=?",
                AuditStore.time(clock.instant()), "f".repeat(64), id);
        worker.runOnce();
        assertEquals("DEAD", store.find(id).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Integer.class));
    }

    @Test
    void failureThresholdAndManualRetryPreserveLifetimeAttempts() {
        properties.setMaxFailures(1);
        long id = enqueue(event());
        var lease = store.claim(store.find(id));
        store.failed(lease, "DELIVERY_FAILED", false);
        assertEquals("DEAD", store.find(id).status());
        var dead = store.find(id);
        assertTrue(tx.<Boolean>execute(status -> store.retry(id, dead.rowVersion(), "admin")));
        var retry = store.find(id);
        assertEquals("RETRY", retry.status());
        assertEquals(1, retry.attempts());
        assertEquals(0, retry.failureCount());
        assertEquals(1, retry.manualRetryCount());
        assertFalse(tx.<Boolean>execute(status -> store.retry(id, dead.rowVersion(), "admin")));
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertFalse(tx.<Boolean>execute(status -> store.retry(id, store.find(id).rowVersion(), "admin")));
    }

    @Test
    void preparedTimeoutBecomesUnknownAndLateResultIsAnotherEvent() {
        var intent = event().toBuilder().phase("EXTERNAL_RESULT").result("UNKNOWN").durationMs(null).build();
        long id = store.prepare(intent);
        assertEquals("PREPARED", store.find(id).status());
        worker.runOnce();
        assertEquals("PREPARED", store.find(id).status());
        clock.advance(121);
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT result FROM sys_operation_log", String.class));
        assertNull(jdbc.queryForObject("SELECT durationMs FROM sys_operation_log", Long.class));
        var known = intent.toBuilder().result("SUCCESS").occurredAt(clock.instant()).build();
        store.completePrepared(id, known);
        worker.runOnce();
        assertEquals(2, jdbcClient.sql("SELECT COUNT(*) FROM sys_operation_log WHERE operationId=?")
                .param(intent.operationId()).query(Integer.class).single());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT eventId) FROM sys_operation_log", Integer.class));
    }

    @Test
    void preparedCompletionAndPayloadBudgetUseUtf8Bytes() {
        var event = event();
        long id = store.prepare(event.toBuilder().result("UNKNOWN").build());
        store.completePrepared(id, event);
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        var snapshots = new AuditSnapshots(objectMapper);
        var big = snapshots.created(snapshots.capture(new AuditObject("PARAM_ITEM", "x", "x", "大数据", null, null),
                Map.of("extra", AuditField.visible("扩展", "汉".repeat(40000)))));
        long large = enqueue(event().toBuilder().objects(List.of(big)).build());
        assertTrue(store.find(large).payloadBytes() > 65536);
        worker.runOnce();
        assertEquals("DONE", store.find(large).status());
        properties.setMaxPayloadBytes(1024);
        var oversized = event().toBuilder().objects(List.of(big)).build();
        assertThrows(IllegalArgumentException.class, () -> enqueue(oversized));
    }

    @Test
    void invalidExpiredIntentDoesNotBlockOtherEvents() {
        long broken = store.prepare(event().toBuilder().result("UNKNOWN").build());
        jdbc.update("UPDATE sys_audit_outbox SET payload='invalid' WHERE id=?", broken);
        long valid = enqueue(event());
        clock.advance(121);
        worker.runOnce();
        assertEquals("DEAD", store.find(broken).status());
        assertEquals("DONE", store.find(valid).status());
    }

    @Test
    void configuredRetryBackoffAndActiveLeaseCannotBeManuallyRetried() {
        long id = enqueue(event());
        for (long delay : new long[]{5, 30, 120, 600, 1800, 3600}) {
            var lease = store.claim(store.find(id));
            assertNotNull(lease);
            assertFalse(tx.<Boolean>execute(status -> store.retry(id, store.find(id).rowVersion(), "admin")));
            store.failed(lease, "DELIVERY_FAILED", false);
            var due = jdbc.queryForObject("SELECT nextRetryAt FROM sys_audit_outbox WHERE id=?", java.time.LocalDateTime.class, id);
            assertEquals(AuditStore.time(clock.instant().plusSeconds(delay)), due);
            assertNull(store.claim(store.find(id)));
            clock.advance(delay);
        }
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertEquals(7, store.find(id).attempts());
    }

    @Test
    void absentSnapshotSidesRemainSqlNullAfterJsonRoundTripAndReferenceDelivery() {
        var ref = new AuditObject("AUDIT_LOG", "1", null, "日志1", null, null);
        var reference = new AuditSnapshots(objectMapper).reference(ref);
        var created = event();
        var original = created.toBuilder().objects(List.of(reference, created.objects().getFirst())).build();
        var decoded = objectMapper.readValue(objectMapper.writeValueAsString(original), AuditEvent.class);
        assertNull(decoded.objects().getFirst().beforeData());
        assertNull(decoded.objects().getFirst().afterData());
        long id = enqueue(original);
        worker.runOnce();
        assertEquals("DONE", store.find(id).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_change WHERE beforeData IS NULL AND afterData IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_change
                WHERE objectNo = 1 AND entryType = 'CHANGE' AND beforeData IS NULL AND afterData IS NOT NULL
                """, Integer.class));
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
