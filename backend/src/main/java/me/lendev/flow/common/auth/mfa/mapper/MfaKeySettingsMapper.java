package me.lendev.flow.common.auth.mfa.mapper;

import static me.lendev.flow.common.auth.mfa.entity.table.MfaKeySettingsTableDef.MFA_KEY_SETTINGS;

import java.time.LocalDateTime;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import me.lendev.flow.common.auth.mfa.entity.MfaKeySettings;

/**
 * 维护 MFA 密钥发布的单例设置。
 */
public interface MfaKeySettingsMapper extends BaseMapper<MfaKeySettings> {

    default MfaKeySettings settings(boolean lock) {
        var query = QueryWrapper.create().where(MFA_KEY_SETTINGS.ID.eq(1));
        return selectOneByQuery(lock ? query.forUpdate() : query);
    }

    default void incrementRevision() {
        revisionUpdate().update();
    }

    default void publish(String target, LocalDateTime now) {
        revisionUpdate().set(MFA_KEY_SETTINGS.TARGET_KEY_ID, target).set(MFA_KEY_SETTINGS.PUBLISHED_AT, now).update();
    }

    private UpdateChain<MfaKeySettings> revisionUpdate() {
        return UpdateChain.of(this).set(MFA_KEY_SETTINGS.REVISION, MFA_KEY_SETTINGS.REVISION.add(1))
                .where(MFA_KEY_SETTINGS.ID.eq(1));
    }
}
