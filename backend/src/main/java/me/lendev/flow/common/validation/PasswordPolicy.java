package me.lendev.flow.common.validation;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.i18n.ApiMessage;
import me.lendev.flow.policy.AuthPolicy;

/**
 * 校验及生成符合当前策略的密码。长度与类别按 Unicode 码点判断。
 */
public final class PasswordPolicy {

    public static final int MAX_LENGTH = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> ALPHABETS = Map.of(
            "uppercase", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "lowercase", "abcdefghijklmnopqrstuvwxyz",
            "digit", "0123456789", "special", "!@#$%&*-_+=?");
    private static final String ALL = String.join("", ALPHABETS.values());

    private PasswordPolicy() {
    }

    /**
     * 校验新密码，包括长度、字符类别及账号名限制。
     */
    public static void validate(String value, String username, AuthPolicy.Password policy) {
        if (value == null || value.isBlank()) {
            throw BadRequestException.localized("validation.password.required");
        }
        int length = value.codePointCount(0, value.length());
        if (length < policy.minLength() || length > MAX_LENGTH) {
            throw BadRequestException.localized("validation.password.length-range", policy.minLength(), MAX_LENGTH);
        }
        if (value.codePoints().anyMatch(PasswordPolicy::invalidCodePoint)) {
            throw BadRequestException.localized("validation.password.invalid-character");
        }
        if (policy.rejectUsername() && username != null && value.equalsIgnoreCase(username)) {
            throw BadRequestException.localized("validation.password.username");
        }
        var missing = new LinkedHashSet<>(policy.requiredCharacterTypes());
        value.codePoints().forEach(codePoint -> missing.remove(characterType(codePoint)));
        if (!missing.isEmpty()) {
            var names = missing.stream().map(type -> new ApiMessage("validation.password.type." + type)).toList();
            throw BadRequestException.localized("validation.password.character-types", names);
        }
    }

    /**
     * 生成独立的临时密码，并随机打散各类必选字符。
     */
    public static String generate(String username, AuthPolicy.Password policy) {
        String value;
        do {
            var chars = policy.requiredCharacterTypes().stream()
                    .map(type -> pick(ALPHABETS.get(type))).collect(Collectors.toCollection(ArrayList::new));
            while (chars.size() < Math.max(16, policy.minLength())) {
                chars.add(pick(ALL));
            }
            Collections.shuffle(chars, RANDOM);
            var result = new StringBuilder(chars.size());
            chars.forEach(result::append);
            value = result.toString();
        } while (policy.rejectUsername() && value.equalsIgnoreCase(username));
        return value;
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }

    private static boolean invalidCodePoint(int codePoint) {
        int kind = Character.getType(codePoint);
        return kind == Character.CONTROL || kind == Character.SURROGATE;
    }

    private static String characterType(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER -> "uppercase";
            case Character.LOWERCASE_LETTER -> "lowercase";
            case Character.DECIMAL_DIGIT_NUMBER -> "digit";
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> "special";
            default -> null;
        };
    }
}
