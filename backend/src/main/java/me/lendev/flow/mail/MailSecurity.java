package me.lendev.flow.mail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 为查询游标和幂等请求摘要提供独立的签名用途。
 */
@Component
final class MailSecurity {
    static final int MAX_CURSOR_LENGTH = 16_384;
    private static final long CURSOR_TTL_SECONDS = Duration.ofMinutes(15).toSeconds();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] key;

    MailSecurity(ObjectMapper json, Clock clock, @Value("${flow.mail.credential-key:}") String configuredKey) {
        this.json = json;
        this.clock = clock;
        this.key = decodeKey(configuredKey);
    }

    String cursor(Object scope, String continuation) {
        if (continuation == null) {
            return null;
        }
        long expires = clock.instant().getEpochSecond() + CURSOR_TTL_SECONDS;
        String encoded = URL_ENCODER.encodeToString(continuation.getBytes(StandardCharsets.UTF_8));
        String cursor = expires + "." + encoded + "." + URL_ENCODER.encodeToString(signature(expires, encoded, scope));
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new MailOperationException(HttpStatus.BAD_GATEWAY, "MAIL_PROVIDER_CURSOR_TOO_LONG", "邮箱服务返回的翻页信息过长");
        }
        return cursor;
    }

    String continuation(Object scope, String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        var fields = cursor.split("\\.", -1);
        if (fields.length != 3 || fields[1].isEmpty()) {
            throw invalidCursor();
        }
        try {
            long expires = Long.parseLong(fields[0]);
            var supplied = Base64.getUrlDecoder().decode(fields[2]);
            if (expires <= clock.instant().getEpochSecond() || !fields[2].equals(URL_ENCODER.encodeToString(supplied))
                    || !MessageDigest.isEqual(signature(expires, fields[1], scope), supplied)) {
                throw invalidCursor();
            }
            return new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw invalidCursor();
        }
    }

    String digest(Object request) {
        return HexFormat.of().formatHex(mac("flow:mail:idempotency:v1", json.writeValueAsBytes(request)));
    }

    private byte[] signature(long expires, String continuation, Object scope) {
        return mac("flow:mail:cursor:v1", json.writeValueAsBytes(List.of(expires, continuation, scope)));
    }

    private byte[] mac(String domain, byte[] payload) {
        if (key.length == 0) {
            throw new MailOperationException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_KEY_UNAVAILABLE", "邮件服务的加密密钥未配置");
        }
        try {
            var signer = Mac.getInstance("HmacSHA256");
            signer.init(new SecretKeySpec(key, "HmacSHA256"));
            signer.update(domain.getBytes(StandardCharsets.UTF_8));
            signer.update((byte) 0);
            return signer.doFinal(payload);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("无法生成邮件请求签名", unavailable);
        }
    }

    private static byte[] decodeKey(String configuredKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey.strip());
            return decoded.length == 32 ? decoded : new byte[0];
        } catch (IllegalArgumentException invalid) {
            return new byte[0];
        }
    }

    private static MailOperationException invalidCursor() {
        return new MailOperationException(HttpStatus.BAD_REQUEST, "MAIL_INBOX_CURSOR_INVALID", "翻页信息无效或已过期，请从第一页重新查询");
    }
}
