package me.lendev.flow.common.auth.mfa.mapper;

import static me.lendev.flow.common.auth.mfa.entity.table.MfaKeyMaterialTableDef.MFA_KEY_MATERIAL;

import java.time.LocalDateTime;
import java.util.List;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import me.lendev.flow.common.auth.mfa.entity.MfaKeyMaterial;

/**
 * 持久化 MFA 密钥草稿、发布状态和密文。
 */
public interface MfaKeyMapper extends BaseMapper<MfaKeyMaterial> {

    default List<MfaKeyMaterial> versions() {
        return selectListByQuery(QueryWrapper.create().select(MFA_KEY_MATERIAL.KEY_ID, MFA_KEY_MATERIAL.CREATED_AT,
                MFA_KEY_MATERIAL.PUBLISHED_AT).orderBy(MFA_KEY_MATERIAL.KEY_ID.asc()));
    }

    default List<MfaKeyMaterial> allWrapped() {
        return selectListByQuery(QueryWrapper.create().select(MFA_KEY_MATERIAL.KEY_ID, MFA_KEY_MATERIAL.KEY_CIPHERTEXT));
    }

    default String publishedSecret(String id) {
        return selectObjectByQueryAs(QueryWrapper.create().select(MFA_KEY_MATERIAL.KEY_CIPHERTEXT)
                .where(MFA_KEY_MATERIAL.KEY_ID.eq(id, true)).and(MFA_KEY_MATERIAL.PUBLISHED_AT.isNotNull()), String.class);
    }

    default void publishDrafts(LocalDateTime now) {
        UpdateChain.of(this).set(MFA_KEY_MATERIAL.PUBLISHED_AT, now)
                .where(MFA_KEY_MATERIAL.PUBLISHED_AT.isNull()).update();
    }
}
