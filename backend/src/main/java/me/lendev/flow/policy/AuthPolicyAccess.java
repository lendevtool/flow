package me.lendev.flow.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 同一认证请求共享一个已提交的策略快照。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthPolicyAccess {

    private static final String KEY = AuthPolicyAccess.class.getName();
    private final AuthPolicyDefinition definition;
    private final PolicyCatalog catalog;

    /**
     * 读取当前认证规则，Web 请求内只读取一次。
     *
     * @return 当前请求绑定的策略快照，无 Web 请求时读取当前已提交的策略
     * @throws PolicyUnavailableException 已保存的策略无法读取或未通过校验
     */
    public AuthPolicy current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(KEY, RequestAttributes.SCOPE_REQUEST) instanceof AuthPolicy policy) {
            return policy;
        }
        var stored = catalog.read(definition, false);
        var config = stored.config();
        try {
            definition.validate(config);
        } catch (me.lendev.flow.common.exception.BadRequestException ex) {
            log.warn("数据库认证策略校验失败，本次策略读取返回不可用，请核对已保存配置和当前部署支持的结构，requestId={} policyCode={} schemaVersion={} revision={}",
                    AuditParticipants.currentRequestId(), definition.code(), stored.schemaVersion(), stored.revision());
            throw new PolicyUnavailableException();
        }
        var policy = definition.decode(config);
        if (request != null) {
            request.setAttribute(KEY, policy, RequestAttributes.SCOPE_REQUEST);
        }
        return policy;
    }
}
