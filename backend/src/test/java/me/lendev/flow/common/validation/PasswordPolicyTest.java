package me.lendev.flow.common.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.lendev.flow.common.auth.PasswordHashes;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.policy.AuthPolicy;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private static final AuthPolicy.Password ALL = new AuthPolicy.Password(
            12, List.of("uppercase", "lowercase", "digit", "special"), true, true, 0, 86400);

    @Test
    void enforcesLengthAndEachSelectedCategory() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Aa1!" + "x".repeat(8), "user", ALL));
        String tooShort = "Aa1!" + "x".repeat(7);
        String tooLong = "Aa1!" + "x".repeat(17);
        assertThrows(BadRequestException.class, () -> PasswordPolicy.validate(tooShort, "user", ALL));
        assertThrows(BadRequestException.class, () -> PasswordPolicy.validate(tooLong, "user", ALL));
        for (String password : List.of("aa1!xxxxxxxx", "AA1!XXXXXXXX", "Aaa!xxxxxxxx", "Aa1 xxxxxxxx")) {
            assertThrows(BadRequestException.class, () -> PasswordPolicy.validate(password, "user", ALL));
        }
        assertDoesNotThrow(() -> PasswordPolicy.validate(PasswordPolicy.generate("user", ALL), "user", ALL));
    }

    @Test
    void hashesAllCodePointsWithoutTruncation() {
        String password = "Aa𝟙" + "😀".repeat(17);
        assertDoesNotThrow(() -> PasswordPolicy.validate(password, "user", ALL));
        String hash = PasswordHashes.encode(password);
        assertTrue(PasswordHashes.matches(password, hash));
        assertFalse(PasswordHashes.matches("Aa𝟙" + "😀".repeat(16) + "😁", hash));
    }

    @Test
    void appliesCustomCombinationsAndEmptySelection() {
        var custom = new AuthPolicy.Password(8, List.of("uppercase", "digit"), false, true, 0, 86400);
        assertDoesNotThrow(() -> PasswordPolicy.validate("AAA11111", "AAA11111", custom));
        assertThrows(BadRequestException.class, () -> PasswordPolicy.validate("aa111111", "user", custom));
        var empty = new AuthPolicy.Password(8, List.of(), false, true, 0, 86400);
        assertDoesNotThrow(() -> PasswordPolicy.validate("中文".repeat(4), "user", empty));
    }
}
