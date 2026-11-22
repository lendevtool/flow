package me.lendev.flow.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MailSecurityTest {
    @Test
    void bindsTheContinuationToTheCallerAndSearch() {
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        var clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
        var security = new MailSecurity(JsonMapper.builder().build(), clock, key);
        String cursor = security.cursor("accountA:user1:subjectA", "provider-cursor");

        assertEquals("provider-cursor", security.continuation("accountA:user1:subjectA", cursor));
        assertThrows(MailOperationException.class, () -> security.continuation("accountA:user2:subjectA", cursor));
        assertThrows(MailOperationException.class, () -> security.continuation("accountA:user1:subjectB", cursor));
        assertThrows(MailOperationException.class, () -> security.continuation("accountA:user1:subjectA", cursor + "a"));
        var later = new MailSecurity(JsonMapper.builder().build(),
                Clock.fixed(clock.instant().plusSeconds(901), ZoneOffset.UTC), key);
        assertThrows(MailOperationException.class, () -> later.continuation("accountA:user1:subjectA", cursor));
    }

    @Test
    void rejectsMalformedAndOversizedCursorsAndFailsClosedWithoutAKey() {
        var mapper = JsonMapper.builder().build();
        var clock = Clock.systemUTC();
        var security = new MailSecurity(mapper, clock, Base64.getEncoder().encodeToString(new byte[32]));
        String oversized = "a".repeat(MailSecurity.MAX_CURSOR_LENGTH + 1);

        assertEquals("MAIL_PROVIDER_CURSOR_TOO_LONG", assertThrows(MailOperationException.class,
                () -> security.cursor("scope", oversized)).code());
        for (String invalid : new String[]{"invalid", "invalid.data.!", oversized}) {
            assertEquals("MAIL_INBOX_CURSOR_INVALID", assertThrows(MailOperationException.class,
                    () -> security.continuation("scope", invalid)).code());
        }

        var unavailable = new MailSecurity(mapper, clock, "invalid!");
        assertEquals("MAIL_KEY_UNAVAILABLE", assertThrows(MailOperationException.class,
                () -> unavailable.digest("some request")).code());
    }
}
