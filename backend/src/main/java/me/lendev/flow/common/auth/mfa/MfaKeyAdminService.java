package me.lendev.flow.common.auth.mfa;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.policy.PolicyConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存 MFA 密钥草稿，并在管理员刷新时统一发布。
 */
@Service
@RequiredArgsConstructor
public class MfaKeyAdminService {

    private final MfaKeyStore keys;
    private final MfaKeyWrapping wrapping;
    private final MfaRotationStore rotations;
    private final MfaRotationService rotation;
    private final Clock clock;

    public record View(long revision, boolean configured, String targetKeyId, Instant lastPublishedAt,
                       List<KeyVersion> versions) {

    }

    public record KeyVersion(String id, String status, boolean eligible, Instant createdAt, Instant publishedAt) {

    }

    /**
     * 只返回管理所需的版本元数据。
     */
    @Transactional(readOnly = true)
    public View view() {
        var settings = keys.settings(false);
        var state = rotations.state(false);
        var versions = keys.versions().stream().map(key -> new KeyVersion(key.id(),
                key.publishedAt() == null ? "DRAFT" : "PUBLISHED", eligible(state, key.id()),
                key.createdAt(), key.publishedAt())).toList();
        return new View(settings.revision(), wrapping.configured(), settings.targetKeyId(),
                settings.publishedAt(), versions);
    }

    /**
     * 生成供当前管理员填写的新数据密钥，保存前仅作为表单输入。
     */
    public String generate() {
        requireWrapping();
        return MfaSecrets.randomDataKey();
    }

    /**
     * 加密保存新版本。草稿在发布前对登录和轮转不可见。
     */
    @Audited(AuditOperation.MFA_KEY_STAGE)
    @Transactional(rollbackFor = Exception.class)
    public void stage(String id, String raw) {
        requireWrapping();
        if (!MfaSecrets.validVersion(id)) {
            throw BadRequestException.localized("error.mfa.keys.version");
        }
        var state = rotations.state(true);
        keys.settings(true);
        var existing = keys.allWrapped();
        if (state.usedVersions().contains(id)
                || existing.stream().anyMatch(key -> key.id().equals(id))) {
            throw BadRequestException.localized("error.mfa.keys.duplicate-version");
        }
        var key = raw == null || raw.length() < 43 || raw.length() > 44 ? null : MfaSecrets.decodeKey(raw);
        if (key == null) {
            throw BadRequestException.localized("error.mfa.keys.format");
        }
        if (wrapping.isWrappingKey(key)
                || existing.stream().anyMatch(version -> key.equals(wrapping.unwrap(version.id(), version.ciphertext())))) {
            throw BadRequestException.localized("error.mfa.keys.duplicate-material");
        }
        keys.insert(id, wrapping.wrap(id, key), clock.instant());
    }

    /**
     * 原子发布密钥和目标版本。选中立即启用时，同时切换新绑定的写入版本。
     */
    @Audited(AuditOperation.MFA_KEY_REFRESH)
    @Transactional(rollbackFor = Exception.class)
    public void refresh(long revision, String target, boolean activate) {
        requireWrapping();
        var state = rotations.state(true);
        if (keys.settings(true).revision() != revision) {
            throw new PolicyConflictException();
        }
        if ((target == null && activate) || (target != null && (!MfaSecrets.validVersion(target)
                || !eligible(state, target)))) {
            throw BadRequestException.localized("error.mfa.keys.target");
        }
        var existing = keys.allWrapped();
        // 发布前逐一校验密文仍可被本实例的上层密钥解开。
        existing.forEach(version -> wrapping.unwrap(version.id(), version.ciphertext()));
        if (target != null && existing.stream().noneMatch(version -> version.id().equals(target))) {
            throw BadRequestException.localized("error.mfa.keys.target");
        }
        keys.publish(target, clock.instant());
        if (activate && !target.equals(state.active())) {
            if ("FAILED".equals(state.status())) {
                throw BadRequestException.localized("error.mfa.rotation.not-ready");
            }
            rotation.activateFromRefresh(state, target);
        }
    }

    private static boolean eligible(MfaRotationStore.State state, String id) {
        return !state.usedVersions().contains(id) || id.equals(state.active());
    }

    private void requireWrapping() {
        if (!wrapping.configured()) {
            throw BadRequestException.localized("error.mfa.keys.storage-unconfigured");
        }
    }
}
