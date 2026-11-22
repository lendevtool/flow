package me.lendev.flow.common.auth.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 使用数据库中的已发布密钥加解密绑定种子，密文关联用户和密钥版本。
 */
@Component
@RequiredArgsConstructor
public class MfaSecrets {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int NONCE_BYTES = 12;
    private static final int DATA_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MfaRotationStore rotation;
    private final MfaKeyStore stored;
    private final MfaKeyWrapping wrapping;

    /**
     * 判断数据库是否已有可用的当前写入版本。
     */
    public boolean configured() {
        return hasKey(rotation.state(false).active());
    }

    boolean hasKey(String version) {
        return resolve(version) != null;
    }

    private SecretKeySpec resolve(String version) {
        if (version == null || !wrapping.configured()) {
            return null;
        }
        String ciphertext = stored.publishedSecret(version);
        return ciphertext == null ? null : wrapping.unwrap(version, ciphertext);
    }

    static boolean validVersion(String version) {
        return version != null && KEY_ID.matcher(version).matches();
    }

    byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    String protect(long userId, byte[] secret) {
        return protect(userId, rotation.state(false).active(), secret);
    }

    String protect(long userId, String version, byte[] secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] encrypted = cipher(userId, version, Cipher.ENCRYPT_MODE, nonce, secret);
        String payload = Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                .put(nonce).put(encrypted).array());
        return version + ":" + payload;
    }

    byte[] open(long userId, String value) {
        try {
            int separator = value.indexOf(':');
            if (separator < 0 || !validVersion(value.substring(0, separator))) {
                throw new MfaUnavailableException();
            }
            String version = value.substring(0, separator);
            var input = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(separator + 1)));
            if (input.remaining() <= NONCE_BYTES) {
                throw new MfaUnavailableException();
            }
            byte[] nonce = new byte[NONCE_BYTES];
            input.get(nonce);
            byte[] encrypted = new byte[input.remaining()];
            input.get(encrypted);
            return cipher(userId, version, Cipher.DECRYPT_MODE, nonce, encrypted);
        } catch (IllegalArgumentException invalid) {
            throw new MfaUnavailableException();
        }
    }

    private byte[] cipher(long userId, String version, int mode, byte[] nonce, byte[] input) {
        var key = resolve(version);
        if (key == null) {
            throw new MfaUnavailableException();
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(userId).array());
            cipher.updateAAD(version.getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException invalid) {
            throw new MfaUnavailableException();
        }
    }

    static SecretKeySpec decodeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == DATA_KEY_BYTES ? new SecretKeySpec(decoded, "AES") : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static String randomDataKey() {
        return Base64.getEncoder().encodeToString(randomBytes(DATA_KEY_BYTES));
    }

    static String randomCode(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes));
    }

    private static byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return value;
    }
}
