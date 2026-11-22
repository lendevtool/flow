package me.lendev.flow.common.auth.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Microsoft Authenticator 通用账户使用的 30 秒、六位 HMAC-SHA1 TOTP。
 */
public final class Totp {

    private static final int STEP_SECONDS = 30;

    private Totp() {
    }

    /**
     * 返回验证通过的时间步。已经使用的时间步不能再次通过。
     */
    public static long verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long step = now.getEpochSecond() / STEP_SECONDS;
        byte[] provided = code.getBytes(StandardCharsets.US_ASCII);
        for (long candidate = step + 1; candidate >= step - 1 && candidate > lastUsedStep; candidate--) {
            if (MessageDigest.isEqual(provided, code(secret, candidate).getBytes(StandardCharsets.US_ASCII))) {
                return candidate;
            }
        }
        return -1;
    }

    // RFC 6238 的 HMAC-SHA1 用于兼容已绑定的验证器。校验端另有尝试限流和时间步防重放。
    @SuppressWarnings("java:S4790")
    static String code(byte[] secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int number = (ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & 0x7fffffff) % 1_000_000;
            return String.format(Locale.ROOT, "%06d", number);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("TOTP algorithm is unavailable", error);
        }
    }
}
