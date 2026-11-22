package me.lendev.flow.common.auth.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用部署侧上层密钥保护数据库中的 MFA 数据密钥。
 */
@Component
class MfaKeyWrapping {

    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec wrappingKey;

    MfaKeyWrapping(@Value("${security.mfa.key-storage-encryption-key:}") String value) {
        wrappingKey = MfaSecrets.decodeKey(value);
        if (!value.isBlank() && wrappingKey == null) {
            throw new IllegalStateException("MFA 密钥存储保护密钥格式无效");
        }
    }

    boolean configured() {
        return wrappingKey != null;
    }

    boolean isWrappingKey(SecretKeySpec key) {
        return key.equals(wrappingKey);
    }

    String wrap(String id, SecretKeySpec key) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] encrypted = crypt(id, Cipher.ENCRYPT_MODE, nonce, key.getEncoded());
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                .put(nonce).put(encrypted).array());
    }

    SecretKeySpec unwrap(String id, String ciphertext) {
        try {
            var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext));
            if (buffer.remaining() <= NONCE_BYTES) {
                throw new MfaUnavailableException();
            }
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            byte[] key = crypt(id, Cipher.DECRYPT_MODE, nonce, encrypted);
            if (key.length != 32) {
                throw new MfaUnavailableException();
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException invalid) {
            throw new MfaUnavailableException();
        }
    }

    private byte[] crypt(String id, int mode, byte[] nonce, byte[] input) {
        if (!configured()) {
            throw new MfaUnavailableException();
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, wrappingKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("flow:mfa:key:" + id).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw new MfaUnavailableException();
        }
    }
}
