package me.lendev.flow.openapi.admin;

import static me.lendev.flow.audit.AuditOperation.OPEN_API_GRANT_UPDATE;
import static me.lendev.flow.openapi.admin.OpenApiAdminMapperSupport.*;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;
import static me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.*;

import java.time.Clock;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.openapi.admin.OpenApiAdminAudit.Mutation;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按应用管理接口授权及限流配置。
 */
@Service
@RequiredArgsConstructor
class OpenApiAdminGrants {

    private final OpenApiAdminService apps;
    private final OpenApiAdminMapper mapper;
    private final OpenApiAdminAudit audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Grant> grants(long appId) {
        apps.requireApp(appId, false);
        return mapper.grants(appId).stream().map(OpenApiAdminGrants::view).toList();
    }

    /**
     * 在应用与目录行锁下检查授权版本。重新授予时保存统一的授权模式和接口配额。
     */
    @Audited(OPEN_API_GRANT_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<Grant> updateGrant(long appId, long endpointId, GrantUpdate request, int actor) {
        apps.requireApp(appId, true);
        EndpointRow endpoint = apps.requireEndpoint(endpointId, true);
        GrantRow before = mapper.lockGrant(appId, endpointId);
        if (request.status() == GrantStatus.REVOKED) {
            return revokeGrant(appId, endpointId, request.version(), actor, before);
        }
        return grant(appId, endpoint, request, actor, before);
    }

    private Mutation<Grant> grant(long appId, EndpointRow endpoint, GrantUpdate request, int actor, GrantRow before) {
        requireVersion(request.version(), before == null ? null : before.version());
        if (endpoint.status() != EndpointStatus.PUBLISHED) {
            throw OpenApiAdminFailure.invalid("只能为已发布的接口配置授权");
        }
        GrantConfig configuration = configuration(request);
        if (before == null) {
            requireWrite(mapper.insertGrant(appId, endpoint.id(), configuration, actor, now(clock)));
        } else if (before.status() == GrantStatus.GRANTED && configuration.equals(before.config())) {
            return Mutation.unchanged(view(before));
        } else {
            requireWrite(mapper.updateGrant(appId, endpoint.id(), before.version(), GrantStatus.GRANTED,
                    configuration, actor, now(clock)));
        }
        GrantRow after = mapper.lockGrant(appId, endpoint.id());
        return Mutation.changed(view(after), audit.grant(before, after));
    }

    private Mutation<Grant> revokeGrant(long appId, long endpointId, Long version, int actor, GrantRow before) {
        if (before == null) {
            throw OpenApiAdminFailure.notFound();
        }
        requireVersion(version, before.version());
        if (before.status() == GrantStatus.REVOKED) {
            return Mutation.unchanged(view(before));
        }
        requireWrite(mapper.revokeGrant(appId, endpointId, before.version(), actor, now(clock)));
        GrantRow after = mapper.lockGrant(appId, endpointId);
        return Mutation.changed(view(after), audit.grant(before, after));
    }

    private static GrantConfig configuration(GrantUpdate request) {
        if (request.dataScopeMode() == DataScopeMode.RESTRICTED
                || request.dataScope() != null && !request.dataScope().isNull()) {
            throw OpenApiAdminFailure.invalid("开放接口统一按应用授权，授权请求无需提交数据范围");
        }
        if ((request.rateLimitCount() == null) != (request.rateLimitWindowSeconds() == null)) {
            throw OpenApiAdminFailure.invalid("接口配额与限流窗口必须同时设置");
        }
        return new GrantConfig(DataScopeMode.NOT_APPLICABLE, null, request.rateLimitCount(), request.rateLimitWindowSeconds());
    }

    private static Grant view(GrantRow row) {
        return new Grant(row.appId(), row.endpointId(), row.endpointCode(), row.displayName(), row.endpointStatus(),
                NO_DATA_SCOPE, row.status(), DataScopeMode.NOT_APPLICABLE, null, row.rateLimitCount(),
                row.rateLimitWindowSeconds(), row.version());
    }
}
