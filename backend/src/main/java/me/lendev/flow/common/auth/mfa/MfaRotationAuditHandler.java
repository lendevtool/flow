package me.lendev.flow.common.auth.mfa;

import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditObject;
import org.springframework.stereotype.Component;

/**
 * 轮转审计仅记录版本、状态和时间。
 */
@Component
@RequiredArgsConstructor
public class MfaRotationAuditHandler implements AuditHandler {

    private static final AuditObject OBJECT = new AuditObject("SYSTEM_POLICY", "MFA_KEY_ROTATION",
            "MFA_KEY_ROTATION", "MFA 密钥轮转", null, null);
    private final MfaRotationStore store;
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.MFA_ROTATION_START, AuditOperation.MFA_ROTATION_COMPLETE, AuditOperation.MFA_ROTATION_FAIL);
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        var before = capture(store.state(true));
        return result -> List.of(snapshots.updated(before, capture(store.state(true))));
    }

    private AuditSnapshots.Capture capture(MfaRotationStore.State state) {
        return snapshots.capture(OBJECT, Map.of(
                "activeKeyId", AuditField.visible("密钥版本", state.active()),
                "activeSince", AuditField.visible("版本启用时间", state.activeSince() == null ? null : state.activeSince().toString()),
                "lastCompletedAt", AuditField.visible("上次成功轮转", state.lastCompleted() == null ? null : state.lastCompleted().toString()),
                "status", AuditField.visible("迁移状态", state.status()),
                "errorCode", AuditField.visible("迁移结果代码", state.error())));
    }
}
