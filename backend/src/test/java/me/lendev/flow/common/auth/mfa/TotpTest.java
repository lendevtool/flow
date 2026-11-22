package me.lendev.flow.common.auth.mfa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TotpTest {

    @Test
    void matchesRfc6238Sha1VectorReducedToSixDigitsAndRejectsReuse() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        var time = Instant.ofEpochSecond(59);
        assertEquals("287082", Totp.code(secret, 1));
        assertEquals(1, Totp.verify(secret, "287082", time, -1));
        assertEquals(-1, Totp.verify(secret, "287082", time, 1));
    }
}
