package me.lendev.flow.common.auth.mfa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.mfa.entity.MfaKeyMaterial;
import me.lendev.flow.common.auth.mfa.mapper.MfaKeyMapper;
import me.lendev.flow.common.auth.mfa.mapper.MfaKeySettingsMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

/**
 * 独立保存待发布和已发布的版本。运行时查询仅返回已发布密文。
 */
@Repository
@RequiredArgsConstructor
class MfaKeyStore {

    private final MfaKeyMapper mapper;
    private final MfaKeySettingsMapper settingsMapper;

    record Settings(long revision, String targetKeyId, Instant publishedAt) {

    }

    record Version(String id, Instant createdAt, Instant publishedAt) {

    }

    record Wrapped(String id, String ciphertext) {

        @Override
        public String toString() {
            return "Wrapped[id=" + id + "]";
        }
    }

    Settings settings(boolean lock) {
        var row = settingsMapper.settings(lock);
        if (row == null) {
            throw new EmptyResultDataAccessException(1);
        }
        return new Settings(row.getRevision(), row.getTargetKeyId(), instant(row.getPublishedAt()));
    }

    List<Version> versions() {
        return mapper.versions().stream()
                .map(row -> new Version(row.getKeyId(), instant(row.getCreatedAt()), instant(row.getPublishedAt()))).toList();
    }

    List<Wrapped> allWrapped() {
        return mapper.allWrapped().stream().map(row -> new Wrapped(row.getKeyId(), row.getKeyCiphertext())).toList();
    }

    String publishedSecret(String id) {
        return mapper.publishedSecret(id);
    }

    void insert(String id, String ciphertext, Instant now) {
        var row = new MfaKeyMaterial();
        row.setKeyId(id);
        row.setKeyCiphertext(ciphertext);
        row.setCreatedAt(utc(now));
        mapper.insertSelectiveWithPk(row);
        settingsMapper.incrementRevision();
    }

    void publish(String target, Instant now) {
        LocalDateTime timestamp = utc(now);
        mapper.publishDrafts(timestamp);
        settingsMapper.publish(target, timestamp);
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
