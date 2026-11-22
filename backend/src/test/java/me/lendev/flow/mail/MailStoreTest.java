package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.mail.MailStore.SendReservation;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles({"h2", "test"})
@Import(JdbcFixtureConfiguration.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:mail-store-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class MailStoreTest {

    @Autowired
    MailStore store;

    @Autowired
    JdbcTemplate jdbc;

    private long accountId;
    private long templateId;
    private String key;

    @BeforeEach
    void fixture() {
        key = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sys_mail_account (code,name,provider,mailboxAddress,configJson,credentialCiphertext,
                                              createdBy,createdAt,updatedBy,updatedAt)
                VALUES (?, 'Operations','GRAPH','send@example.com','{}','encrypted',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                """, key);
        accountId = jdbc.queryForObject("SELECT id FROM sys_mail_account WHERE code=?", Long.class, key);
        jdbc.update("""
                INSERT INTO sys_mail_template (code,name,accountId,subjectTemplate,bodyTemplate,
                                               createdBy,createdAt,updatedBy,updatedAt)
                VALUES (?, 'Notice',?,'Subject','Body',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                """, key, accountId);
        templateId = jdbc.queryForObject("SELECT id FROM sys_mail_template WHERE code=?", Long.class, key);
    }

    @Test
    void concurrentReservationsReturnTheSameCommittedLogAndAUniqueWinner() throws Exception {
        var start = new CyclicBarrier(2);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return store.reserve(reservation(), groups());
            });
            var second = callers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return store.reserve(reservation(), groups());
            });

            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(a.log().id(), b.log().id());
            assertEquals(a.details(), b.details());
            assertEquals(2, a.details().size());
            assertNotEquals(a.created(), b.created());
        }
    }

    @Test
    void expiredRequestSeparatesUncertainFromNeverStartedDetails() {
        var send = store.reserve(reservation(), groups());
        long first = send.details().getFirst().id();
        assertTrue(store.claim(first));
        assertFalse(store.claim(first));
        assertEquals(Status.PROCESSING, store.send(send.log().id()).log().status());

        jdbc.update("UPDATE sys_mail_send_log SET createdAt=? WHERE id=?", "2000-01-01 00:00:00", send.log().id());
        assertTrue(store.expire(Instant.parse("2000-01-02T00:00:00Z")) >= 1);
        store.finish(first, new Submission(Status.ACCEPTED, null, null, null, List.of()));
        var finished = store.send(send.log().id());
        assertEquals(Status.UNKNOWN, finished.log().status());
        assertEquals(Status.UNKNOWN, finished.details().getFirst().status());
        assertEquals(Status.FAILED, finished.details().getLast().status());
        assertFalse(store.claim(finished.details().getLast().id()));
        assertNotNull(finished.log().finishedAt());
    }

    @Test
    void skippedRecipientsAndConfirmedAcceptedRecipientsProducePartialStatus() {
        var send = store.reserve(reservation(), groups());
        assertTrue(store.findSend("USER:1", null).isEmpty());
        assertEquals(send.log(), store.findSend("USER:1", key).orElseThrow().log());

        long first = send.details().getFirst().id();
        assertTrue(store.claim(first));
        var recipients = List.of(new RecipientResult("first@example.com", Status.ACCEPTED));
        store.finish(first, new Submission(Status.ACCEPTED, null, null, null, recipients));

        var unfinished = new Submission(Status.PROCESSING, null, null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> store.finish(first, unfinished));
        long second = send.details().getLast().id();
        store.skip(second, "MAIL_SEND_BUDGET_EXHAUSTED", "本次发送已达到处理时限");
        var result = store.send(send.log().id());
        assertEquals(Status.PARTIAL, result.log().status());
        assertEquals(recipients, result.details().getFirst().recipientResults());
        assertEquals("MAIL_SEND_BUDGET_EXHAUSTED", result.details().getLast().errorCode());
        assertNull(result.details().getLast().startedAt());
        assertFalse(store.claim(Long.MAX_VALUE));
    }

    @Test
    void requestThatExpiresWithoutSubmittingHasFailedStatus() {
        var send = store.reserve(reservation(), groups());
        jdbc.update("UPDATE sys_mail_send_log SET createdAt=? WHERE id=?", "2000-01-01 00:00:00", send.log().id());
        assertEquals(1, store.expire(Instant.parse("2000-01-02T00:00:00Z")));
        var result = store.send(send.log().id());
        assertEquals(Status.FAILED, result.log().status());
        assertTrue(result.details().stream().allMatch(detail -> detail.status() == Status.FAILED && detail.startedAt() == null));
    }

    private SendReservation reservation() {
        return new SendReservation(accountId, templateId, key, key, 1, "send@example.com",
                Provider.GRAPH, SendMode.INDIVIDUAL, "USER:1", key, key, "b".repeat(64), 2);
    }

    private List<List<Recipient>> groups() {
        return List.of(List.of(new Recipient("first@example.com", null)),
                List.of(new Recipient("second@example.com", null)));
    }
}
