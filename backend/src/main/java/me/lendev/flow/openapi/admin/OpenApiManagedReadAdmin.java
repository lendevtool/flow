package me.lendev.flow.openapi.admin;

import static me.lendev.flow.audit.AuditOperation.OPEN_API_ENDPOINT_IMPORT;
import static me.lendev.flow.audit.AuditOperation.OPEN_API_ENDPOINT_SOURCE_CONFIRM;
import static me.lendev.flow.openapi.admin.OpenApiAdminMapperSupport.now;
import static me.lendev.flow.openapi.admin.OpenApiAdminMapperSupport.requireVersion;
import static me.lendev.flow.openapi.admin.OpenApiAdminMapperSupport.requireWrite;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.NO_DATA_SCOPE;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.openapi.ManagedReadRegistry;
import me.lendev.flow.openapi.admin.OpenApiAdminAudit.Mutation;
import me.lendev.flow.openapi.admin.OpenApiAdminModels.ConfirmSourceContract;
import me.lendev.flow.openapi.admin.OpenApiAdminModels.Endpoint;
import me.lendev.flow.openapi.admin.OpenApiAdminModels.EndpointSource;
import me.lendev.flow.openapi.admin.OpenApiAdminModels.EndpointStatus;
import me.lendev.flow.openapi.admin.OpenApiAdminModels.ImportEndpoint;
import me.lendev.flow.openapi.admin.OpenApiAdminModels.SourceRestriction;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.ReadEndpointInsert;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在接口目录中登记经模块审查的只读来源，并管理其已发布契约。
 */
@Service
@RequiredArgsConstructor
class OpenApiManagedReadAdmin {
    private final OpenApiAdminMapper mapper;
    private final OpenApiAdminService admin;
    private final OpenApiAdminAudit audit;
    private final ManagedReadRegistry registry;
    private final Clock clock;

    List<EndpointSource> sources(Boolean importable) {
        return registry.routes().stream().map(this::sourceView)
                .filter(source -> importable == null || importable == source.importable())
                .sorted(Comparator.comparing(EndpointSource::importable).reversed()).toList();
    }

    private EndpointSource sourceView(ManagedReadRegistry.Route route) {
        boolean get = "GET".equals(route.method());
        var source = get ? registry.source(route.path()) : null;
        if (source == null) {
            int slash = route.path().indexOf('/', 1);
            String module = slash < 0 ? route.path().substring(1) : route.path().substring(1, slash);
            var reason = get ? SourceRestriction.POLICY_REQUIRED : SourceRestriction.READ_ONLY;
            return new EndpointSource(route.path(), route.method(), module, false, reason, null, List.of(), List.of(), false, false, false);
        }
        var contract = source.fullContract();
        return new EndpointSource(source.path(), route.method(), source.moduleCode(), true, null, NO_DATA_SCOPE,
                contract.parameters(), contract.fields(), contract.collection(), contract.page(), contract.wholeResponse());
    }

    /**
     * 以同一事务保存目录项及其来源映射，路径或 Scope 重复时返回冲突。
     */
    @Audited(OPEN_API_ENDPOINT_IMPORT)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<Endpoint> importEndpoint(ImportEndpoint request, int actor) {
        var source = registry.source(request.sourcePath());
        if (source == null || !ManagedReadRegistry.isPublicPath(request.pathTemplate())) {
            throw OpenApiAdminFailure.invalid("来源或对外路径不支持配置开放");
        }
        var responseFields = request.responseFields();
        var selected = responseFields == null ? Set.<String>of() : new HashSet<>(responseFields);
        var contract = source.contract(selected);
        if (contract == null || responseFields != null && selected.size() != responseFields.size()) {
            throw OpenApiAdminFailure.invalid("请选择不同的可公开响应字段");
        }
        var insert = new ReadEndpointInsert(request.endpointCode(), source.moduleCode(), request.displayName().strip(),
                request.pathTemplate(), source.dataScopeType(), actor, now(clock));
        try {
            requireWrite(mapper.insertReadEndpoint(insert));
        } catch (DuplicateKeyException e) {
            throw OpenApiAdminFailure.endpointConflict();
        }
        requireWrite(mapper.insertReadMapping(insert.getId(), source.path(), registry.writeContract(contract), source.hash()));
        var after = admin.requireEndpoint(insert.getId(), false);
        return Mutation.changed(admin.endpointView(after), audit.endpointSource(null, after));
    }

    /**
     * 在接口下线期间重新确认来源。已发布的客户端契约必须能够保持一致。
     */
    @Audited(OPEN_API_ENDPOINT_SOURCE_CONFIRM)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<Endpoint> confirmSource(long endpointId, ConfirmSourceContract request, int actor) {
        var before = admin.requireEndpoint(endpointId, true);
        requireVersion(request.version(), before.version());
        if (before.status() != EndpointStatus.OFFLINE || before.sourcePath() == null) {
            throw OpenApiAdminFailure.invalid("仅已下线的配置开放接口可以重新确认");
        }
        var source = registry.source(before.sourcePath());
        var contract = registry.readContract(before.publicContractJson());
        if (source == null || contract == null || !contract.equals(source.contract(contract.fieldNames()))
                || !before.moduleCode().equals(source.moduleCode()) || !before.dataScopeType().equals(source.dataScopeType())) {
            throw OpenApiAdminFailure.registrationMismatch();
        }
        if (source.hash().equals(before.sourceContractHash())) {
            return Mutation.unchanged(admin.endpointView(before));
        }
        requireWrite(mapper.updateEndpointStatus(endpointId, before.version(), before.status(), actor, now(clock)));
        requireWrite(mapper.refreshReadMapping(endpointId, source.hash()));
        var after = admin.requireEndpoint(endpointId, false);
        return Mutation.changed(admin.endpointView(after), audit.endpointSource(before, after));
    }
}
