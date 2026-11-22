package me.lendev.flow.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MailSchemaTest {

    @Test
    void sendReservationKeyIsUniquePerCallerAndOptional() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:mail-schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/h2.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update("""
                INSERT INTO sys_mail_account (id, code, name, provider, mailboxAddress, configJson, credentialCiphertext,
                                              enable, createdBy, createdAt, updatedBy, updatedAt)
                VALUES (1, 'OPS', 'Operations', 'GRAPH', 'ops@example.com', '{}', 'ciphertext', 1,
                        1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO sys_mail_template (id, code, name, accountId, subjectTemplate, bodyTemplate, bodyFormat,
                                               sendMode, revision, enable, createdBy, createdAt, updatedBy, updatedAt)
                VALUES (1, 'NOTICE', 'Notice', 1, 'Subject', 'Body', 'MARKDOWN', 'TOGETHER', 1, 1,
                        1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """);

        insert(jdbc, "USER:1", "related-order");
        assertThrows(DataIntegrityViolationException.class, () -> insert(jdbc, "USER:1", "related-order"));
        insert(jdbc, "USER:2", "related-order");
        insert(jdbc, "USER:1", null);
        insert(jdbc, "USER:1", null);
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sys_mail_send_log", Integer.class));
    }

    private void insert(JdbcTemplate jdbc, String callerKey, String key) {
        jdbc.update("""
                INSERT INTO sys_mail_send_log (accountId, templateId, accountCode, templateCode, templateRevision,
                                               senderAddress, provider, sendMode, callerKey, requestId, idempotencyKey,
                                               requestDigest, recipientCount, status, createdAt)
                VALUES (1, 1, 'OPS', 'NOTICE', 1, 'ops@example.com', 'GRAPH', 'TOGETHER', ?, 'request', ?, ?, 1,
                        'PENDING', CURRENT_TIMESTAMP)
                """, callerKey, key, key == null ? null : "a".repeat(64));
    }
}
