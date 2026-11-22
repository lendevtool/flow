package me.lendev.flow.common.auth.mfa;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.codec.Base32;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.extra.qrcode.QrCodeUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.auth.AccountSession;
import me.lendev.flow.common.auth.LoginMethod;
import me.lendev.flow.common.auth.SessionRules;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.policy.AuthPolicy;
import me.lendev.flow.policy.AuthPolicyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录二次验证和个人绑定共用的 MFA 流程。
 */
@Service
@RequiredArgsConstructor
public class MfaService {

    private final MfaStore store;
    private final MfaTickets tickets;
    private final MfaSecrets secrets;
    private final MfaRotationStore rotation;
    private final AuthPolicyAccess policies;
    private final Clock clock;

    public record Challenge(String ticket, boolean enrollment, String qrCode, String setupKey, long expiresInSeconds) {

        @Override
        public String toString() {
            return "Challenge[enrollment=" + enrollment + "]";
        }
    }

    public record Status(boolean configured, boolean globallyRequired, boolean personalEnabled, boolean bound,
                         boolean recentRecoveryLogin) {

    }

    public record Proof(String bindingId, boolean recovered) {

    }

    public record Verified(User user, LoginMethod method, Proof proof, List<String> recoveryCodes) {

        @Override
        public String toString() {
            return "Verified[method=" + method + "]";
        }
    }

    public record Enrollment(List<String> recoveryCodes) {

        @Override
        public String toString() {
            return "Enrollment[confirmed]";
        }
    }

    /**
     * 第一因素通过后，判断是否需要阻止签发会话并进入 MFA 挑战。
     */
    public Challenge beginLogin(User user, LoginMethod method, AuthPolicy policy) {
        var state = store.state(user.getId());
        if (!policy.mfa().required() && !state.enabled()) {
            return null;
        }
        requireConfigured();
        return challenge(user, method, MfaTickets.Purpose.LOGIN, state.bindingId(), !state.bound(), null);
    }

    /**
     * 完成登录挑战，并在初次绑定时原子写入绑定和首个时间步。
     */
    @Transactional(rollbackFor = Exception.class)
    public Verified verifyLogin(String id, String code) {
        requireConfigured();
        var stored = tickets.get(id, MfaTickets.Purpose.LOGIN);
        var ticket = stored.ticket();
        tickets.checkAttempt(ticket.userId());
        var user = store.lockUser(ticket.userId());
        if (user == null || !Boolean.TRUE.equals(user.getEnable())
                || !Objects.equals(user.getIdentityKey(), ticket.identityKey())) {
            throw MfaTickets.expired();
        }
        var methods = policies.current().login();
        boolean local = ticket.method() == LoginMethod.LOCAL;
        if (local ? !methods.localEnabled() : !methods.domainEnabled()) {
            throw MfaTickets.expired();
        }
        if (local && user.getTemporaryPasswordExpiresAt() != null
                && !clock.instant().isBefore(user.getTemporaryPasswordExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw MfaTickets.expired();
        }

        var binding = matchingBinding(ticket);
        if (ticket.newSecret() != null) {
            var created = enroll(stored, code, false, false);
            return new Verified(user, ticket.method(), new Proof(created.bindingId(), false), created.codes());
        }
        boolean recovered = verifyExisting(user.getId(), binding, code);
        tickets.consume(stored);
        return new Verified(user, ticket.method(), new Proof(binding.id(), recovered), List.of());
    }

    /**
     * 个人设置返回生效规则和绑定状态。
     */
    public Status status(long userId) {
        var state = store.state(userId);
        return new Status(secrets.configured(), policies.current().mfa().required(), state.enabled(), state.bound(),
                SessionRules.recoveredRecently(state.bindingId(), clock.instant()));
    }

    /**
     * 生成绑定二维码，更换现有绑定时先验证旧验证码或恢复码。
     */
    @Transactional(rollbackFor = Exception.class)
    public Challenge startSetup(long userId, String currentCode) {
        requireConfigured();
        var user = authenticatedUser(userId);
        var binding = store.binding(userId);
        if (binding != null && !SessionRules.recoveredRecently(binding.id(), clock.instant())) {
            tickets.checkAttempt(userId);
            verifyExisting(userId, binding, currentCode);
        }
        return challenge(user, null, MfaTickets.Purpose.PROFILE, binding == null ? null : binding.id(), true, sessionHash());
    }

    /**
     * 验证新设备的首个验证码后开启个人 MFA。
     */
    @Audited(AuditOperation.MFA_BIND)
    @Transactional(rollbackFor = Exception.class)
    public Enrollment confirmSetup(long userId, String id, String code) {
        requireConfigured();
        var stored = tickets.get(id, MfaTickets.Purpose.PROFILE);
        var ticket = stored.ticket();
        if (ticket.userId() != userId || !Objects.equals(ticket.sessionHash(), sessionHash())) {
            throw MfaTickets.expired();
        }
        tickets.checkAttempt(userId);
        var user = authenticatedUser(userId);
        if (!Objects.equals(user.getIdentityKey(), ticket.identityKey())) {
            throw MfaTickets.expired();
        }
        var previous = matchingBinding(ticket);
        return new Enrollment(enroll(stored, code, true, previous != null).codes());
    }

    /**
     * 全局关闭时允许个人关闭 MFA，保留绑定以便之后恢复启用。
     */
    @Audited(AuditOperation.MFA_PREFERENCE)
    @Transactional(rollbackFor = Exception.class)
    public Status preference(long userId, boolean enabled, String code) {
        requireConfigured();
        authenticatedUser(userId);
        if (!enabled && policies.current().mfa().required()) {
            throw BadRequestException.localized("error.mfa.globally-required");
        }
        var binding = store.binding(userId);
        if (binding == null) {
            throw BadRequestException.localized("error.mfa.binding-required");
        }
        if (binding.enabled() != enabled) {
            tickets.checkAttempt(userId);
            verifyExisting(userId, binding, code);
            store.setEnabled(userId, enabled);
        }
        return status(userId);
    }

    private Challenge challenge(User user, LoginMethod method, MfaTickets.Purpose purpose, String bindingId,
                                boolean enrollment, String sessionHash) {
        byte[] secret = enrollment ? secrets.newSecret() : null;
        String key = enrollment ? Base32.encode(secret) : null;
        String qr = enrollment ? QrCodeUtil.generateAsBase64(
                "otpauth://totp/Flow:" + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8).replace("+", "%20")
                        + "?secret=" + key + "&issuer=Flow&algorithm=SHA1&digits=6&period=30",
                cn.hutool.extra.qrcode.QrConfig.create().setWidth(240).setHeight(240), "png") : null;
        String encrypted = enrollment ? secrets.protect(user.getId(), secret) : null;
        String id = tickets.issue(new MfaTickets.Ticket(purpose, user.getId(), user.getIdentityKey(), method,
                bindingId, encrypted, sessionHash));
        return new Challenge(id, enrollment, qr, key, MfaTickets.LIFETIME.toSeconds());
    }

    private MfaStore.Binding matchingBinding(MfaTickets.Ticket ticket) {
        var binding = store.binding(ticket.userId());
        String currentId = binding == null ? null : binding.id();
        if (!Objects.equals(ticket.bindingId(), currentId)) {
            throw MfaTickets.expired();
        }
        return binding;
    }

    private Created enroll(MfaTickets.Stored stored, String code, boolean personallyEnabled, boolean existed) {
        var ticket = stored.ticket();
        byte[] secret = secrets.open(ticket.userId(), ticket.newSecret());
        long step = Totp.verify(secret, code, clock.instant(), -1);
        if (step < 0) {
            throw invalidCode();
        }
        tickets.consume(stored);
        String bindingId = UUID.randomUUID().toString();
        List<String> codes = IntStream.range(0, 8).mapToObj(index -> MfaSecrets.randomCode(12)).toList();
        // 与版本切换串行化，旧挑战仍使用当前写入版本落库。
        String active = rotation.state(true).active();
        store.replace(ticket.userId(), new MfaStore.Binding(bindingId, personallyEnabled,
                        secrets.protect(ticket.userId(), active, secret), step, codes.stream().map(DigestUtil::sha256Hex).toList()),
                active, existed);
        return new Created(bindingId, codes);
    }

    private boolean verifyExisting(long userId, MfaStore.Binding binding, String code) {
        if (binding == null || code == null) {
            throw invalidCode();
        }
        String submitted = code.strip();
        long step = Totp.verify(secrets.open(userId, binding.secret()), submitted, clock.instant(), binding.usedStep());
        if (step >= 0) {
            store.markStep(userId, step);
            return false;
        }
        var hashes = new ArrayList<>(binding.recoveryHashes());
        if (submitted.length() == 16 && hashes.remove(DigestUtil.sha256Hex(submitted))) {
            store.remainingCodes(userId, hashes);
            return true;
        }
        throw invalidCode();
    }

    private User authenticatedUser(long userId) {
        var user = store.lockUser(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        AccountSession.check(user);
        return user;
    }

    private static String sessionHash() {
        return DigestUtil.sha256Hex(StpUtil.getTokenValue());
    }

    private void requireConfigured() {
        if (!secrets.configured()) {
            throw new MfaUnavailableException();
        }
    }

    private static BadRequestException invalidCode() {
        return BadRequestException.localized("error.mfa.invalid-code");
    }

    private record Created(String bindingId, List<String> codes) {

    }
}
