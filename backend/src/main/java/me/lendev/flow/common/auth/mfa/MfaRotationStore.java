package me.lendev.flow.common.auth.mfa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.mfa.mapper.MfaBindingMapper;
import me.lendev.flow.common.auth.mfa.mapper.MfaRotationMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 密钥版本切换状态与待重加密绑定的持久化入口。
 */
@Repository
@RequiredArgsConstructor
class MfaRotationStore {

    private static final TypeReference<List<String>> VERSIONS = new TypeReference<>() {
    };
    private final MfaRotationMapper mapper;
    private final MfaBindingMapper bindings;
    private final ObjectMapper json;

    record State(String active, Instant activeSince, Instant lastCompleted, String status, String error,
                 List<String> usedVersions) {

    }

    record Pending(long userId, String version, String ciphertext) {

        @Override
        public String toString() {
            return "Pending[userId=" + userId + ", version=" + version + "]";
        }
    }

    State state(boolean lock) {
        var row = mapper.state(lock);
        if (row == null) {
            throw new EmptyResultDataAccessException(1);
        }
        return new State(row.getActiveKeyId(), instant(row.getActiveSince()), instant(row.getLastCompletedAt()), row.getStatus(), row.getErrorCode(),
                json.readValue(row.getUsedVersions(), VERSIONS));
    }

    List<String> versions() {
        return bindings.versions();
    }

    long pendingCount(String active) {
        return bindings.pendingCount(active);
    }

    List<Pending> batch(String active) {
        return bindings.pendingBindings(active).stream().map(row -> new Pending(row.getUserId(), row.getKeyVersion(), row.getSecretCiphertext())).toList();
    }

    void rewrite(Pending previous, String active, String ciphertext) {
        // 绑定或其他实例先写入时保留较新的结果。
        bindings.rewriteIfCurrent(previous.userId(), previous.version(), previous.ciphertext(), active, ciphertext);
    }

    void activate(State previous, String target, Instant now) {
        var history = new ArrayList<>(previous.usedVersions());
        history.add(target);
        mapper.activate(target, utc(now), json.writeValueAsString(history), previous.active() == null ? "IDLE" : "RUNNING");
    }

    void resume() {
        mapper.resume();
    }

    void complete(Instant now) {
        mapper.complete(utc(now));
    }

    void fail() {
        mapper.fail();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
