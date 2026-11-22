package me.lendev.flow.common.auth.mfa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditObject;
import org.springframework.stereotype.Component;

/**
 * 仅记录密钥版本和发布状态，密钥材料不参与审计快照。
 */
@Component
@RequiredArgsConstructor
public class MfaKeyAuditHandler implements AuditHandler {

    private static final AuditObject OBJECT = new AuditObject("SYSTEM_POLICY", "MFA_KEY_MANAGEMENT",
            "MFA_KEY_MANAGEMENT", "MFA 密钥管理", null, null);
    private final MfaKeyStore keys;
    private final MfaRotationStore rotations;
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.MFA_KEY_STAGE, AuditOperation.MFA_KEY_REFRESH);
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        boolean refresh = operation == AuditOperation.MFA_KEY_REFRESH;
        String stageId = !refresh && args[0] instanceof String id && MfaSecrets.validVersion(id) ? id : null;
        var before = capture(refresh, stageId);
        return result -> List.of(snapshots.updated(before, capture(refresh, stageId)));
    }

    private AuditSnapshots.Capture capture(boolean refresh, String stageId) {
        var state = rotations.state(true);
        var settings = keys.settings(true);
        var fields = new LinkedHashMap<String, AuditField>();
        var versions = keys.versions();
        if (stageId != null) {
            String label = "密钥版本 " + stageId;
            var version = versions.stream().filter(key -> key.id().equals(stageId)).findFirst();
            fields.put("versions." + stageId, version
                    .map(key -> AuditField.visible(label, key.publishedAt() == null ? "待发布" : "已发布"))
                    .orElseGet(() -> AuditField.missing(label, true)));
        }
        if (refresh) {
            fields.put("publishedVersions", AuditField.visible("已发布版本", versions.stream()
                    .filter(key -> key.publishedAt() != null).map(MfaKeyStore.Version::id).toList()));
            fields.put("activeKeyId", AuditField.visible("当前写入版本", state.active()));
            fields.put("targetKeyId", AuditField.visible("轮转目标版本", settings.targetKeyId()));
            fields.put("status", AuditField.visible("迁移状态", state.status()));
            fields.put("revision", AuditField.visible("密钥配置修订号", settings.revision()));
        }
        return snapshots.capture(OBJECT, fields);
    }
}
