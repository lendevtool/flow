package me.lendev.flow.openapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 生成开放 API 凭据并提供固定长度摘要校验。
 */
public final class OpenApiCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern CLIENT_ID = Pattern.compile("fa_[A-Za-z0-9_-]{22}");
    private static final Pattern SECRET = Pattern.compile("fsk_[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN = Pattern.compile("fat_[A-Za-z0-9_-]{43}");

    private OpenApiCredentials() {
    }

    /**
     * @return 新的随机应用外部标识
     */
    public static String newClientId() {
        return random("fa_", 16);
    }

    /**
     * @return 仅用于一次性交付的新应用密钥
     */
    public static String newSecret() {
        return random("fsk_", 32);
    }

    /**
     * @return 带算法标识的持久化密钥摘要
     */
    public static String secretHash(String secret) {
        return "sha256:" + digest(secret);
    }

    static String newToken() {
        return random("fat_", 32);
    }

    static boolean validClientId(String value) {
        return value.length() == 25 && CLIENT_ID.matcher(value).matches();
    }

    static boolean validSecret(String value) {
        return value.length() == 47 && SECRET.matcher(value).matches();
    }

    static boolean validToken(String value) {
        return value.length() == 47 && TOKEN.matcher(value).matches();
    }

    static boolean equalHashes(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String random(String prefix, int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return prefix + ENCODER.encodeToString(bytes);
    }

    public static String digest(String value) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
