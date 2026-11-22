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
 * 审计绑定和开关的状态，不采集验证码、密钥或恢复码。
 */
@Component
@RequiredArgsConstructor
public class MfaAuditHandler implements AuditHandler {

    private final MfaStore store;
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.MFA_BIND, AuditOperation.MFA_PREFERENCE);
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        long userId = (Long) args[0];
        var user = store.lockUser(userId);
        if (user == null) {
            throw me.lendev.flow.common.exception.BadRequestException.localized("error.user.missing");
        }
        var object = new AuditObject("USER", Long.toString(userId), user.getUsername(), user.getUsername(), null, null);
        var before = capture(object, store.state(userId));
        return result -> List.of(snapshots.updated(before, capture(object, store.state(userId))));
    }

    private AuditSnapshots.Capture capture(AuditObject object, MfaStore.State state) {
        return snapshots.capture(object, Map.of(
                "mfa.bound", AuditField.visible("已绑定 MFA", state.bound()),
                "mfa.personalEnabled", AuditField.visible("个人 MFA 开关", state.enabled()),
                "mfa.binding", AuditField.hidden("MFA 设备绑定", state.bindingId())));
    }
}
