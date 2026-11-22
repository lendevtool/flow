package me.lendev.flow.common.auth.mfa.mapper;

import static me.lendev.flow.common.auth.mfa.entity.table.MfaKeyRotationTableDef.MFA_KEY_ROTATION;

import java.time.LocalDateTime;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import me.lendev.flow.common.auth.mfa.entity.MfaKeyRotation;

/**
 * 读取和更新 MFA 密钥轮转的单例状态。
 */
public interface MfaRotationMapper extends BaseMapper<MfaKeyRotation> {

    default MfaKeyRotation state(boolean lock) {
        var query = QueryWrapper.create().where(MFA_KEY_ROTATION.ID.eq(1));
        return selectOneByQuery(lock ? query.forUpdate() : query);
    }

    default void activate(String target, LocalDateTime now, String history, String status) {
        statusUpdate(status, null).set(MFA_KEY_ROTATION.ACTIVE_KEY_ID, target).set(MFA_KEY_ROTATION.ACTIVE_SINCE, now)
                .set(MFA_KEY_ROTATION.USED_VERSIONS, history).update();
    }

    default void resume() {
        statusUpdate("RUNNING", null).update();
    }

    default void complete(LocalDateTime now) {
        statusUpdate("COMPLETED", null).set(MFA_KEY_ROTATION.LAST_COMPLETED_AT, now).update();
    }

    default void fail() {
        statusUpdate("FAILED", "DECRYPTION_UNAVAILABLE").update();
    }

    private UpdateChain<MfaKeyRotation> statusUpdate(String status, String error) {
        return UpdateChain.of(this).set(MFA_KEY_ROTATION.STATUS, status).set(MFA_KEY_ROTATION.ERROR_CODE, error)
                .where(MFA_KEY_ROTATION.ID.eq(1));
    }
}
