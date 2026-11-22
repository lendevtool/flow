package me.lendev.flow.policy;

import cn.dev33.satoken.stp.StpUtil;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 整组校验、并发控制和默认值恢复。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PolicyService {

    private final PolicyCatalog catalog;
    private final PolicyRepository repository;
    private final Clock clock;

    /**
     * 策略管理视图，版本号用于下次更新或恢复默认值时的并发校验。
     */
    public record View(String code, int schemaVersion, long revision, JsonNode config,
                       JsonNode defaults, Instant updatedAt, Long updatedBy) {

    }

    public View get(String code) {
        var definition = catalog.require(code);
        return view(definition, catalog.read(definition, false));
    }

    /**
     * 按版本整体替换策略。校验并规范化配置后，仅在配置发生变化时递增版本。
     *
     * @param code     已注册的策略编码
     * @param revision 调用方读取到的版本
     * @param config   完整的新配置
     * @return 保存后的策略视图，无变化时返回原版本
     * @throws PolicyConflictException 调用方版本已过期
     */
    @Audited(AuditOperation.POLICY_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public View update(String code, long revision, JsonNode config) {
        return save(catalog.require(code), revision, config);
    }

    /**
     * 将策略恢复为注册定义提供的默认配置，并执行与普通更新相同的校验。
     *
     * @param code     已注册的策略编码
     * @param revision 调用方读取到的版本
     * @return 恢复后的策略视图，已是默认配置时返回原版本
     * @throws PolicyConflictException 调用方版本已过期
     */
    @Audited(AuditOperation.POLICY_RESET)
    @Transactional(rollbackFor = Exception.class)
    public View reset(String code, long revision) {
        var definition = catalog.require(code);
        return save(definition, revision, definition.defaults());
    }

    private View save(PolicyDefinition definition, long revision, JsonNode input) {
        var previous = catalog.read(definition, true);
        if (previous.revision() != revision) {
            log.info("系统策略版本冲突，requestId={} policyCode={} expectedRevision={} currentRevision={}",
                    AuditParticipants.currentRequestId(), previous.code(), revision, previous.revision());
            throw new PolicyConflictException();
        }
        var config = definition.normalize(input);
        if (previous.config().equals(config)) {
            return view(definition, previous);
        }
        Instant now = clock.instant();
        definition.beforeChange(previous.config(), config, now);
        if (!repository.update(previous, config, StpUtil.getLoginIdAsLong(), now)) {
            log.info("系统策略条件写入未命中，requestId={} policyCode={} expectedRevision={}",
                    AuditParticipants.currentRequestId(), previous.code(), revision);
            throw new PolicyConflictException();
        }
        var saved = catalog.read(definition, true);
        log.info("系统策略配置已写入，requestId={} policyCode={} previousRevision={} revision={}",
                AuditParticipants.currentRequestId(), saved.code(), previous.revision(), saved.revision());
        return view(definition, saved);
    }

    private View view(PolicyDefinition definition, PolicyRepository.Stored stored) {
        return new View(stored.code(), stored.schemaVersion(), stored.revision(), stored.config(),
                definition.defaults(), stored.updatedAt(), stored.updatedBy());
    }
}
