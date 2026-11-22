package me.lendev.flow.openapi.admin;

import static me.lendev.flow.audit.AuditOperation.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.AppRow;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.EndpointRow;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.GrantRow;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.SecretRow;
import org.springframework.stereotype.Component;

/**
 * 仅对明确列出的开放 API 业务配置和凭据版本元数据生成审计快照。
 */
@Component
@RequiredArgsConstructor
class OpenApiAdminAudit implements AuditHandler {

    private static final String STATUS = "status";
    private static final String APP_OBJECT_TYPE = "OPEN_API_APP";

    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(OPEN_API_ENDPOINT_STATUS, OPEN_API_ENDPOINT_IMPORT, OPEN_API_ENDPOINT_SOURCE_CONFIRM,
                OPEN_API_APP_CREATE, OPEN_API_APP_UPDATE, OPEN_API_APP_STATUS,
                OPEN_API_GRANT_UPDATE, OPEN_API_SECRET_GENERATE, OPEN_API_SECRET_ACTIVATE, OPEN_API_SECRET_REVOKE);
    }

    @Override
    public void validate(Method method) {
        if (method.getReturnType() != Mutation.class) {
            throw new IllegalStateException("开放 API 审计需要实际变更明细");
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return new Plan() {
            @Override
            public List<AuditChange> after(Object result) {
                return ((Mutation<?>) result).changes();
            }

            @Override
            public boolean recordSuccess(Object result) {
                return !((Mutation<?>) result).changes().isEmpty();
            }
        };
    }

    AuditChange endpoint(EndpointRow before, EndpointRow after) {
        var object = object("OPEN_API_ENDPOINT", after.id(), after.displayName());
        return compare(object, Map.of(STATUS, value("发布状态", before.status())),
                Map.of(STATUS, value("发布状态", after.status())));
    }

    AuditChange endpointSource(EndpointRow before, EndpointRow after) {
        var object = object("OPEN_API_ENDPOINT", after.id(), after.displayName());
        return compare(object, before == null ? null : endpointSourceFields(before), endpointSourceFields(after));
    }

    private static Map<String, AuditField> endpointSourceFields(EndpointRow row) {
        return Map.of("endpointCode", value("接口编码", row.endpointCode()),
                "pathTemplate", value("对外路径", row.pathTemplate()),
                "sourcePath", value("来源路径", row.sourcePath()),
                "publicContract", value("公开契约", row.publicContractJson()),
                "sourceContractHash", value("来源契约指纹", row.sourceContractHash()));
    }

    AuditChange app(AppRow before, AppRow after) {
        var object = object(APP_OBJECT_TYPE, after.id(), after.name());
        return compare(object, before == null ? null : appFields(before), appFields(after));
    }

    AuditChange grant(GrantRow before, GrantRow after) {
        var object = new AuditObject("OPEN_API_GRANT", after.appId() + "/" + after.endpointId(), after.endpointCode(),
                after.displayName(), APP_OBJECT_TYPE, Long.toString(after.appId()));
        return compare(object, before == null ? null : grantFields(before), grantFields(after));
    }

    AuditChange secret(long appId, SecretRow before, SecretRow after) {
        String version = Integer.toString(after.versionNo());
        var object = new AuditObject("OPEN_API_SECRET", appId + "/" + version, version, "凭据版本 " + version,
                APP_OBJECT_TYPE, Long.toString(appId));
        return compare(object, before == null ? null : secretFields(before), secretFields(after));
    }

    private AuditChange compare(AuditObject object, Map<String, AuditField> before, Map<String, AuditField> after) {
        var next = snapshots.capture(object, after);
        return before == null ? snapshots.created(next) : snapshots.updated(snapshots.capture(object, before), next);
    }

    private static Map<String, AuditField> appFields(AppRow row) {
        return Map.of("name", value("应用名称", row.name()),
                "environment", value("部署环境", row.environment()),
                "enabled", value("启用状态", row.enabled()),
                "description", value("用途", row.description()),
                "ownerUserId", value("负责人", row.ownerUserId()),
                "rateLimitCount", value("应用配额", row.rateLimitCount()),
                "rateLimitWindowSeconds", value("限流窗口（秒）", row.rateLimitWindowSeconds()),
                "tokenRevision", value("令牌版本", row.tokenRevision()));
    }

    private static Map<String, AuditField> grantFields(GrantRow row) {
        return Map.of(STATUS, value("授权状态", row.status()), "dataScopeMode", value("范围模式", row.mode()),
                "dataScope", value("数据范围", row.scopeJson()),
                "rateLimitCount", value("接口配额", row.rateLimitCount()),
                "rateLimitWindowSeconds", value("限流窗口（秒）", row.rateLimitWindowSeconds()));
    }

    private static Map<String, AuditField> secretFields(SecretRow row) {
        return Map.of("versionNo", value("凭据版本", row.versionNo()),
                STATUS, value("凭据状态", row.status()), "expiresAt", value("到期时间", row.expiresAt()),
                "activatedAt", value("激活时间", row.activatedAt()), "revokedAt", value("停止签发时间", row.revokedAt()),
                "tokensRevokedAt", value("令牌作废时间", row.tokensRevokedAt()));
    }

    private static AuditObject object(String type, long id, String name) {
        return new AuditObject(type, Long.toString(id), null, name, null, null);
    }

    private static AuditField value(String label, Object value) {
        return AuditField.visible(label, value);
    }

    /**
     * 业务返回值和允许入队的审计明细由事务入口分别提供。
     */
    record Mutation<T>(T response, List<AuditChange> changes) {
        static <T> Mutation<T> unchanged(T response) {
            return new Mutation<>(response, List.of());
        }

        static <T> Mutation<T> changed(T response, AuditChange change) {
            return new Mutation<>(response, List.of(change));
        }
    }
}
