package me.lendev.flow.policy;

import static me.lendev.flow.policy.entity.table.SystemPolicyTableDef.SYSTEM_POLICY;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.policy.entity.SystemPolicy;
import me.lendev.flow.policy.mapper.PolicyMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 按策略编码读写当前生效记录，修改通过版本条件防止覆盖。
 */
@Repository
@RequiredArgsConstructor
public class PolicyRepository {

    private final PolicyMapper mapper;
    private final ObjectMapper json;

    public record Stored(String code, JsonNode config, int schemaVersion, long revision,
                         Instant updatedAt, Long updatedBy) {

    }

    /**
     * 查询当前策略，缺少记录时返回 {@code null}。需要加锁时由调用方开启事务。
     */
    public Stored find(String code, boolean lock) {
        var query = QueryWrapper.create().where(SYSTEM_POLICY.POLICY_CODE.eq(code, true));
        var row = mapper.selectOneByQuery(lock ? query.forUpdate() : query);
        if (row == null) {
            return null;
        }
        return new Stored(row.getPolicyCode(), json.readTree(row.getConfig()), row.getSchemaVersion(), row.getRevision(),
                row.getUpdatedAt().toInstant(ZoneOffset.UTC), row.getUpdatedBy());
    }

    public void insert(PolicyDefinition definition, JsonNode config, Instant now) {
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        var row = new SystemPolicy();
        row.setPolicyCode(definition.code());
        row.setConfig(json.writeValueAsString(config));
        row.setSchemaVersion(definition.schemaVersion());
        row.setCreatedAt(timestamp);
        row.setUpdatedAt(timestamp);
        mapper.insertSelective(row);
    }

    /**
     * 按读取时的版本更新策略，返回 {@code false} 表示版本已变更或记录已删除。
     */
    public boolean update(Stored previous, JsonNode config, long user, Instant now) {
        return UpdateChain.of(mapper)
                .set(SYSTEM_POLICY.CONFIG, json.writeValueAsString(config))
                .set(SYSTEM_POLICY.REVISION, SYSTEM_POLICY.REVISION.add(1))
                .set(SYSTEM_POLICY.UPDATED_AT, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .set(SYSTEM_POLICY.UPDATED_BY, user)
                .where(SYSTEM_POLICY.POLICY_CODE.eq(previous.code(), true))
                .and(SYSTEM_POLICY.REVISION.eq(previous.revision())).update();
    }
}
