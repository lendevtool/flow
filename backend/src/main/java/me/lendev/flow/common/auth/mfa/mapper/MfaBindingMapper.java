package me.lendev.flow.common.auth.mfa.mapper;

import static com.mybatisflex.core.query.QueryMethods.distinct;
import static me.lendev.flow.common.auth.mfa.entity.table.MfaBindingRecordTableDef.MFA_BINDING_RECORD;

import java.util.List;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import me.lendev.flow.common.auth.mfa.entity.MfaBindingRecord;

/**
 * 读取 MFA 绑定状态，并在调用方持有账号行锁时读写绑定凭据。
 */
public interface MfaBindingMapper extends BaseMapper<MfaBindingRecord> {

    default MfaBindingRecord state(long userId) {
        return selectOneByQuery(QueryWrapper.create()
                .select(MFA_BINDING_RECORD.BINDING_ID, MFA_BINDING_RECORD.PERSONAL_ENABLED)
                .where(MFA_BINDING_RECORD.USER_ID.eq(userId)));
    }

    default MfaBindingRecord lockBinding(long userId) {
        return selectOneByQuery(QueryWrapper.create().where(MFA_BINDING_RECORD.USER_ID.eq(userId)).forUpdate());
    }

    default void markStep(long userId, long step) {
        UpdateChain.of(this).set(MFA_BINDING_RECORD.LAST_USED_STEP, step)
                .where(MFA_BINDING_RECORD.USER_ID.eq(userId)).update();
    }

    default void updateRecoveryHashes(long userId, String hashes) {
        UpdateChain.of(this).set(MFA_BINDING_RECORD.RECOVERY_HASHES, hashes)
                .where(MFA_BINDING_RECORD.USER_ID.eq(userId)).update();
    }

    default void setEnabled(long userId, boolean enabled) {
        UpdateChain.of(this).set(MFA_BINDING_RECORD.PERSONAL_ENABLED, enabled)
                .where(MFA_BINDING_RECORD.USER_ID.eq(userId)).update();
    }

    default List<String> versions() {
        return selectObjectListByQueryAs(QueryWrapper.create().select(distinct(MFA_BINDING_RECORD.KEY_VERSION)), String.class);
    }

    default long pendingCount(String active) {
        return selectCountByQuery(QueryWrapper.create().where(MFA_BINDING_RECORD.KEY_VERSION.ne(active, true)));
    }

    default List<MfaBindingRecord> pendingBindings(String active) {
        return selectListByQuery(QueryWrapper.create()
                .select(MFA_BINDING_RECORD.USER_ID, MFA_BINDING_RECORD.KEY_VERSION, MFA_BINDING_RECORD.SECRET_CIPHERTEXT)
                .where(MFA_BINDING_RECORD.KEY_VERSION.ne(active, true))
                .orderBy(MFA_BINDING_RECORD.KEY_VERSION.asc(), MFA_BINDING_RECORD.USER_ID.asc()).limit(100));
    }

    /**
     * 只在绑定仍使用原密钥和密文时写入轮转结果。
     */
    default void rewriteIfCurrent(long userId, String previousVersion, String previousCiphertext, String active, String ciphertext) {
        UpdateChain.of(this).set(MFA_BINDING_RECORD.KEY_VERSION, active).set(MFA_BINDING_RECORD.SECRET_CIPHERTEXT, ciphertext)
                .where(MFA_BINDING_RECORD.USER_ID.eq(userId)).and(MFA_BINDING_RECORD.KEY_VERSION.eq(previousVersion, true))
                .and(MFA_BINDING_RECORD.SECRET_CIPHERTEXT.eq(previousCiphertext, true)).update();
    }
}
