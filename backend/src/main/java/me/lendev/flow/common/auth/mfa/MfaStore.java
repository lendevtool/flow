package me.lendev.flow.common.auth.mfa;

import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.mfa.mapper.MfaBindingMapper;
import me.lendev.flow.common.auth.mfa.entity.MfaBindingRecord;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.mapper.UserMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * MFA 持久化与账号行锁，所有凭据修改沿用先锁用户再写绑定的顺序。
 */
@Repository
@RequiredArgsConstructor
public class MfaStore {

    private static final TypeReference<List<String>> HASH_LIST = new TypeReference<>() {
    };
    private final MfaBindingMapper mapper;
    private final UserMapper users;
    private final ObjectMapper json;

    record Binding(String id, boolean enabled, String secret, long usedStep, List<String> recoveryHashes) {

    }

    public record State(String bindingId, boolean enabled) {

        public boolean bound() {
            return bindingId != null;
        }
    }

    /**
     * 读取鉴权需要的绑定标识和个人开关，不读取凭据。
     */
    public State state(long userId) {
        var row = mapper.state(userId);
        return row == null ? new State(null, false) : new State(row.getBindingId(), row.getPersonalEnabled());
    }

    User lockUser(long userId) {
        return users.selectOneByQuery(QueryWrapper.create().where(USER.ID.eq(userId)).forUpdate());
    }

    Binding binding(long userId) {
        var row = mapper.lockBinding(userId);
        return row == null ? null : new Binding(row.getBindingId(), row.getPersonalEnabled(), row.getSecretCiphertext(), row.getLastUsedStep(),
                json.readValue(row.getRecoveryHashes(), HASH_LIST));
    }

    void replace(long userId, Binding binding, String keyVersion, boolean existed) {
        var row = new MfaBindingRecord();
        row.setUserId(userId);
        row.setBindingId(binding.id());
        row.setPersonalEnabled(binding.enabled());
        row.setSecretCiphertext(binding.secret());
        row.setLastUsedStep(binding.usedStep());
        row.setRecoveryHashes(json.writeValueAsString(binding.recoveryHashes()));
        row.setKeyVersion(keyVersion);
        if (existed) {
            mapper.update(row);
        } else {
            mapper.insertWithPk(row);
        }
    }

    void markStep(long userId, long step) {
        mapper.markStep(userId, step);
    }

    void remainingCodes(long userId, List<String> hashes) {
        mapper.updateRecoveryHashes(userId, json.writeValueAsString(hashes));
    }

    void setEnabled(long userId, boolean enabled) {
        mapper.setEnabled(userId, enabled);
    }
}
