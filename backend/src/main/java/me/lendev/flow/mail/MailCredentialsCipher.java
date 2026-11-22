package me.lendev.flow.mail;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import me.lendev.flow.mail.MailModels.Credentials;
import me.lendev.flow.mail.MailModels.Provider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 使用独立派生密钥加密邮箱凭据，密文包含版本和随机 nonce。
 */
@Component
final class MailCredentialsCipher {

    private static final String VERSION = "v1.";
    private static final String ENCRYPTION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] KEY_PURPOSE = "flow:mail:credentials:v1".getBytes(UTF_8);

    private final ObjectMapper json;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    MailCredentialsCipher(ObjectMapper json, @Value("${flow.mail.credential-key:}") String configuredKey) {
        this.json = json;
        this.key = deriveKey(configuredKey);
    }

    String seal(Provider provider, Credentials credentials) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, provider, nonce).doFinal(json.writeValueAsBytes(credentials));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException | JacksonException failure) {
            throw credentialFailure();
        }
    }

    Credentials open(Provider provider, String ciphertext) {
        requireKey();
        if (ciphertext == null || !ciphertext.startsWith(VERSION)) {
            throw credentialFailure();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(ciphertext.substring(VERSION.length()));
            if (bytes.length <= NONCE_BYTES + TAG_BITS / 8) {
                throw credentialFailure();
            }
            var buffer = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return json.readValue(cipher(Cipher.DECRYPT_MODE, provider, nonce).doFinal(encrypted), Credentials.class);
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException failure) {
            throw credentialFailure();
        }
    }

    private Cipher cipher(int mode, Provider provider, byte[] nonce) throws GeneralSecurityException {
        var cipher = Cipher.getInstance(ENCRYPTION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(provider.name().getBytes(UTF_8));
        return cipher;
    }

    private static SecretKeySpec deriveKey(String configured) {
        try {
            byte[] bytes = Base64.getDecoder().decode(configured.strip());
            if (bytes.length != 32) {
                return null;
            }
            var derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(bytes, "HmacSHA256"));
            return new SecretKeySpec(derivation.doFinal(KEY_PURPOSE), "AES");
        } catch (IllegalArgumentException invalid) {
            return null;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("无法派生邮箱凭据加密密钥", failure);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new MailOperationException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_KEY_UNAVAILABLE", "邮件服务的加密密钥未配置或无效");
        }
    }

    private static MailOperationException credentialFailure() {
        return new MailOperationException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_CREDENTIAL_UNAVAILABLE", "邮件服务无法访问已配置的凭据");
    }
}
