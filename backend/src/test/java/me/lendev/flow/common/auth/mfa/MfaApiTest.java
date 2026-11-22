package me.lendev.flow.common.auth.mfa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import cn.hutool.core.codec.Base32;
import cn.hutool.extra.qrcode.BufferedImageLuminanceSource;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import me.lendev.flow.common.auth.domain.DomainAuthenticator;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flow_mfa;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "security.mfa.key-storage-encryption-key=QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
})
@Import(MfaApiTest.ClockConfiguration.class)
class MfaApiTest extends ApiTestBase {

    private static final String POLICY = "/system-policy/auth-security";
    private static final String MFA = "/auth/mfa";
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @MockitoBean
    private DomainAuthenticator domain;
    @Autowired
    private MfaKeyStore keys;
    @Autowired
    private MfaKeyWrapping wrapping;
    @Autowired
    private MfaRotationStore rotation;

    @BeforeEach
    void resetMfaWindow() {
        Instant now = Instant.now();
        clock.now.set(now);
        var dataKey = MfaSecrets.decodeKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        keys.insert("v1", wrapping.wrap("v1", dataKey), now);
        keys.publish("v1", now);
        rotation.activate(rotation.state(true), "v1", now);
        redis.delete("flow:auth:mfa:attempts:1");
    }

    @Test
    void forcedLocalLoginEnrollsBeforeIssuingTokenAndRejectsReplay() throws Exception {
        String admin = loginAsAdmin();
        configure(admin, true, false);
        body(getJson("/user/detail", admin), 401);

        var setup = firstFactor(ADMIN, ADMIN_PASSWORD, "LOCAL");
        assertTrue(setup.path("enrollment").asBoolean());
        assertEquals(300, setup.path("expiresInSeconds").asInt());
        String key = setup.path("setupKey").asString();
        String qrCode = setup.path("qrCode").asString();
        assertTrue(qrCode.startsWith("data:image/png;base64,"));
        var png = Base64.getDecoder().decode(qrCode.substring(qrCode.indexOf(',') + 1));
        assertTrue(decodeGeneratedQr(png).contains("secret=" + key));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_mfa WHERE user_id=1", Integer.class));

        String valid = codeFor(key);
        body(verify(setup, "123456".equals(valid) ? "654321" : "123456"), 400);
        var signedIn = data(verify(setup, valid));
        String token = signedIn.path("accessToken").asString();
        assertEquals(8, signedIn.path("recoveryCodes").size());
        String ciphertext = jdbc.queryForObject("SELECT secret_ciphertext FROM sys_user_mfa WHERE user_id=1", String.class);
        assertFalse(ciphertext.contains(key));
        var status = data(getJson(MFA, token));
        assertTrue(status.path("globallyRequired").asBoolean());
        assertFalse(status.path("personalEnabled").asBoolean());
        body(putJson(MFA + "/preference", token, map("enabled", false, "code", valid)), 400);

        body(verify(setup, valid), 400);
        var challenge = firstFactor(ADMIN, ADMIN_PASSWORD, "LOCAL");
        assertFalse(challenge.path("enrollment").asBoolean());
        body(verify(challenge, valid), 400);
        clock.now.updateAndGet(now -> now.plusSeconds(60));
        var next = data(verify(challenge, codeFor(key)));
        data(getJson("/user/detail", next.path("accessToken").asString()));
        String audit = jdbc.queryForObject("""
                SELECT payload FROM sys_audit_outbox WHERE operationCode = 'system.auth.mfa.verify'
                ORDER BY id DESC LIMIT 1
                """, String.class);
        assertFalse(audit.contains(key));
        assertFalse(audit.contains(setup.path("ticket").asString()));
        assertFalse(audit.contains(signedIn.path("recoveryCodes").get(0).asString()));
    }

    @Test
    void optionalBindingCanBeEnabledDisabledAndReenabledWithSingleUseRecoveryCodes() throws Exception {
        String admin = loginAsAdmin();
        configure(admin, false, false);
        var setup = data(postJson(MFA + "/setup", admin, map()));
        body(verify(setup, codeFor(setup.path("setupKey").asString())), 400);
        var enrollment = data(postJson(MFA + "/setup/confirm", admin, map(
                "ticket", setup.path("ticket").asString(), "code", codeFor(setup.path("setupKey").asString()))));
        var codes = enrollment.path("recoveryCodes");
        body(getJson("/user/detail", admin), 401);

        var challenge = firstFactor(ADMIN, ADMIN_PASSWORD, "LOCAL");
        var token = data(verify(challenge, codes.get(0).asString())).path("accessToken").asString();
        var second = firstFactor(ADMIN, ADMIN_PASSWORD, "LOCAL");
        body(verify(second, codes.get(0).asString()), 400);
        var disabled = data(putJson(MFA + "/preference", token, map("enabled", false, "code", codes.get(1).asString())));
        assertFalse(disabled.path("personalEnabled").asBoolean());
        assertTrue(disabled.path("bound").asBoolean());

        var plain = data(postJson("/auth/login", map("username", ADMIN, "password", ADMIN_PASSWORD)));
        assertTrue(plain.path("mfaChallenge").isNull());
        var plainToken = plain.path("accessToken").asString();
        var enabled = data(putJson(MFA + "/preference", plainToken, map("enabled", true, "code", codes.get(2).asString())));
        assertTrue(enabled.path("personalEnabled").asBoolean());
        body(getJson("/user/detail", plainToken), 401);
    }

    @Test
    void domainLoginUsesSameMfaAndCanReplaceBindingUsingRecoveryCode() throws Exception {
        String admin = loginAsAdmin();
        data(postJson("/user", admin, map("username", "mfa_domain", "roleIds", List.of(2),
                "domainAccount", "mfa.domain@company.example")));
        when(domain.configured()).thenReturn(true);
        when(domain.authenticate("mfa.domain@company.example", "mock-domain-password")).thenReturn(true);
        configure(admin, true, true);

        var setup = firstFactor("mfa.domain@company.example", "mock-domain-password", "DOMAIN");
        var signedIn = data(verify(setup, codeFor(setup.path("setupKey").asString())));
        String initialToken = signedIn.path("accessToken").asString();
        assertFalse(signedIn.path("passwordChangeRequired").asBoolean());
        assertEquals("DOMAIN", data(getJson("/user/detail", initialToken)).path("loginMethod").asString());
        body(postJson(MFA + "/setup", initialToken, map()), 400);
        var recoveryChallenge = firstFactor("mfa.domain@company.example", "mock-domain-password", "DOMAIN");
        var recovery = data(verify(recoveryChallenge, signedIn.path("recoveryCodes").get(0).asString()));
        String token = recovery.path("accessToken").asString();
        assertTrue(data(getJson(MFA, token)).path("recentRecoveryLogin").asBoolean());

        var replacement = data(postJson(MFA + "/setup", token, map()));
        var newKey = replacement.path("setupKey").asString();
        assertNotEquals(setup.path("setupKey").asString(), newKey);
        data(postJson(MFA + "/setup/confirm", token,
                map("ticket", replacement.path("ticket").asString(), "code", codeFor(newKey))));
        body(getJson("/user/detail", token), 401);
        clock.now.updateAndGet(now -> now.plusSeconds(60));
        var next = firstFactor("mfa.domain@company.example", "mock-domain-password", "DOMAIN");
        var verified = data(verify(next, codeFor(newKey)));
        assertEquals("DOMAIN", data(getJson("/user/detail", verified.path("accessToken").asString()))
                .path("loginMethod").asString());
    }

    private static String decodeGeneratedQr(byte[] png) throws IOException, ReaderException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "MFA enrollment must return a readable PNG");
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        // 页面响应是直接生成的二维码，不需要照片扫描使用的定位和透视检测。
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, true)).getText();
    }

    private JsonNode firstFactor(String username, String password, String method) throws Exception {
        var first = data(postJson("/auth/login", map("username", username, "password", password, "loginMethod", method)));
        assertTrue(first.path("accessToken").isNull());
        assertTrue(first.path("mfaChallenge").isObject());
        return first.path("mfaChallenge");
    }

    private ResultActions verify(JsonNode challenge, String code) throws Exception {
        return postJson(MFA + "/verify", map("ticket", challenge.path("ticket").asString(), "code", code));
    }

    private String codeFor(String key) {
        return Totp.code(Base32.decode(key), clock.instant().getEpochSecond() / 30);
    }

    private void configure(String token, boolean required, boolean domainEnabled) throws Exception {
        var current = data(getJson(POLICY, token));
        var config = (ObjectNode) current.path("config");
        ((ObjectNode) config.path("captcha")).put("required", false);
        ((ObjectNode) config.path("mfa")).put("required", required);
        ((ObjectNode) config.path("login")).put("domainEnabled", domainEnabled);
        data(putJson(POLICY, token, map("revision", current.path("revision").asLong(), "config", config)));
    }

    private ResultActions putJson(String url, String token, Object data) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(data)));
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mfaTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
