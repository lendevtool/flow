package me.lendev.flow.common.auth;

import cn.hutool.crypto.digest.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * 新密码采用 Argon2id PHC 格式，存量 BCrypt 按原格式验证。
 */
public final class PasswordHashes {

    private static final int MEMORY_KIB = 19456;
    private static final int ITERATIONS = 2;
    private static final int HASH_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final String PREFIX = "$argon2id$v=19$m=19456,t=2,p=1$";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final String DUMMY = encode("non-existent account");

    private PasswordHashes() {
    }

    public static String encode(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + BASE64.encodeToString(salt) + "$" + BASE64.encodeToString(hash(password, salt));
    }

    /**
     * 使用原有算法验证存量密码，并对不存在的账号执行同等的哈希工作。
     */
    public static boolean matches(String password, String encoded) {
        if (password == null || password.length() > 72) {
            return false;
        }
        if (encoded == null) {
            matches(password, DUMMY);
            return false;
        }
        try {
            if (encoded.startsWith("$2")) {
                return password.getBytes(StandardCharsets.UTF_8).length <= 72 && BCrypt.checkpw(password, encoded);
            }
            if (!encoded.startsWith(PREFIX)) {
                return false;
            }
            var parts = encoded.substring(PREFIX.length()).split("\\$", -1);
            if (parts.length != 2) {
                return false;
            }
            byte[] salt = DECODER.decode(parts[0]);
            byte[] expected = DECODER.decode(parts[1]);
            return salt.length == SALT_BYTES && expected.length == HASH_BYTES
                    && MessageDigest.isEqual(expected, hash(password, salt));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] hash(String password, byte[] salt) {
        var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS).withParallelism(1).withSalt(salt).build();
        var generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[HASH_BYTES];
        try {
            generator.generateBytes(secret, result);
            return result;
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
