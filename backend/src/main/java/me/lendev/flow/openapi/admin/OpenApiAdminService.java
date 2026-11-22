package me.lendev.flow.openapi.admin;

import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;
import static me.lendev.flow.openapi.admin.OpenApiAdminMapperSupport.*;
import static me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.openapi.ManagedReadRegistry;
import me.lendev.flow.openapi.OpenApiCredentials;
import me.lendev.flow.openapi.OpenApiProperties;
import me.lendev.flow.openapi.OpenApiRegistry;
import me.lendev.flow.openapi.admin.OpenApiAdminAudit.Mutation;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 管理本环境的接入应用、接口目录以及历史调用查询。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class OpenApiAdminService {

    private static final int DEFAULT_RATE_LIMIT_COUNT = 600;
    private static final int DEFAULT_RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final Duration DEFAULT_CALL_RANGE = Duration.ofHours(24);
    private static final Duration MAX_CALL_RANGE = Duration.ofDays(31);
    private static final Instant FIRST_DATABASE_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant LAST_DATABASE_TIME = Instant.parse("9999-12-31T23:59:59.999Z");

    private final OpenApiAdminMapper mapper;
    private final OpenApiAdminAudit audit;
    private final OpenApiRegistry registry;
    private final ManagedReadRegistry managed;
    private final OpenApiProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<Endpoint> endpoints(String moduleCode, EndpointStatus status, String keyword, Pagination pagination) {
        String search = contains(keyword);
        return page(mapper.pageEndpoints(environment(), moduleCode, status, search, pagination).stream()
                .map(this::endpointView).toList(), mapper.countEndpoints(moduleCode, status, search));
    }

    public Endpoint endpoint(long id) {
        return endpointView(requireEndpoint(id, false));
    }

    @Transactional(readOnly = true)
    public Page<App> endpointApps(long endpointId, Pagination pagination) {
        requireEndpoint(endpointId, false);
        return page(mapper.pageEndpointApps(endpointId, environment(), pagination).stream()
                .map(OpenApiAdminService::appView).toList(), mapper.countEndpointApps(endpointId, environment()));
    }

    /**
     * 锁定接口行并验证部署登记及版本，只有实际状态变化才递增版本。
     *
     * @param id      接口目录 ID
     * @param request 目标发布状态和调用方持有的版本
     * @param actor   操作用户 ID
     * @return 操作后的接口目录项及其审计变更
     */
    @Audited(OPEN_API_ENDPOINT_STATUS)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<Endpoint> updateEndpoint(long id, EndpointUpdate request, int actor) {
        if (request.status() == EndpointStatus.REGISTERED) {
            throw OpenApiAdminFailure.invalid("发布状态只接受 PUBLISHED 或 OFFLINE");
        }
        EndpointRow before = requireEndpoint(id, true);
        requireVersion(request.version(), before.version());
        if ((request.status() == EndpointStatus.PUBLISHED || before.sourcePath() == null) && !registered(before)) {
            throw OpenApiAdminFailure.registrationMismatch();
        }
        if (before.status() == request.status()) {
            return Mutation.unchanged(endpointView(before));
        }
        requireWrite(mapper.updateEndpointStatus(id, before.version(), request.status(), actor, now(clock)));
        EndpointRow after = requireEndpoint(id, false);
        log.info("开放 API 接口发布状态已写入，requestId={} endpointId={} status={} version={}",
                AuditParticipants.currentRequestId(), id, after.status(), after.version());
        return Mutation.changed(endpointView(after), audit.endpoint(before, after));
    }

    List<EndpointRow> publishedEndpointsV1() {
        return mapper.publishedEndpointsV1().stream().filter(this::registered).toList();
    }

    private boolean registered(EndpointRow endpoint) {
        if (endpoint.sourcePath() != null) {
            return managed.matches(endpoint.pathTemplate(), endpoint.httpMethod(), endpoint.apiVersion(), endpoint.dataScopeType(),
                    endpoint.sourcePath(), endpoint.sourceContractHash(), endpoint.publicContractJson());
        }
        return registry.matches(endpoint.endpointCode(), endpoint.httpMethod(), endpoint.pathTemplate(),
                endpoint.apiVersion(), endpoint.dataScopeType());
    }

    @Transactional(readOnly = true)
    public Page<App> apps(String keyword, String selectedEnvironment, Boolean enabled, Pagination pagination) {
        String name = contains(keyword);
        String client = StringUtils.hasText(keyword) ? containsLiteral(keyword.strip()) : null;
        return page(mapper.pageApps(environment(), selectedEnvironment, enabled, name, client, pagination).stream()
                .map(OpenApiAdminService::appView).toList(), mapper.countApps(environment(), selectedEnvironment, enabled, name, client));
    }

    public App app(long id) {
        return appView(requireApp(id, false));
    }

    /**
     * 创建独立的外部应用标识，凭据和接口授权由后续操作建立。
     */
    @Audited(OPEN_API_APP_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<App> createApp(CreateApp request, int actor) {
        String name = name(request.name());
        var config = new AppConfig(name, checkedOwner(request.ownerUserId()), request.description(),
                Objects.requireNonNullElse(request.rateLimitCount(), DEFAULT_RATE_LIMIT_COUNT),
                Objects.requireNonNullElse(request.rateLimitWindowSeconds(), DEFAULT_RATE_LIMIT_WINDOW_SECONDS));
        var insert = new NewApp(OpenApiCredentials.newClientId(), environment(), config, actor, now(clock));
        requireWrite(mapper.insertApp(insert));
        AppRow created = requireApp(insert.getId(), false);
        return Mutation.changed(appView(created), audit.app(null, created));
    }

    /**
     * 在应用行锁下合并部分更新并检查原版本。
     */
    @Audited(OPEN_API_APP_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<App> patchApp(long id, PatchApp request, int actor) {
        AppRow before = requireApp(id, true);
        requireVersion(request.getVersion(), before.version());
        AppConfig next = new AppConfig(
                request.getName() == null ? before.name() : name(request.getName()),
                patchOwner(before.ownerUserId(), request),
                request.isDescriptionSupplied() ? request.getDescription() : before.description(),
                Objects.requireNonNullElse(request.getRateLimitCount(), before.rateLimitCount()),
                Objects.requireNonNullElse(request.getRateLimitWindowSeconds(), before.rateLimitWindowSeconds()));
        if (next.equals(before.config())) {
            return Mutation.unchanged(appView(before));
        }
        requireWrite(mapper.updateApp(id, environment(), before.version(), next, actor, now(clock)));
        AppRow after = requireApp(id, false);
        return Mutation.changed(appView(after), audit.app(before, after));
    }

    /**
     * 只在从启用切换到停用时递增令牌版本。
     *
     * @param id      应用 ID
     * @param request 目标启停状态和调用方持有的版本
     * @param actor   操作用户 ID
     * @return 操作后的应用视图及其审计变更
     */
    @Audited(OPEN_API_APP_STATUS)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<App> updateAppStatus(long id, AppStatus request, int actor) {
        AppRow before = requireApp(id, true);
        requireVersion(request.version(), before.version());
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        if (enabled == before.enabled()) {
            return Mutation.unchanged(appView(before));
        }
        long tokenRevision = enabled ? before.tokenRevision() : before.tokenRevision() + 1;
        requireWrite(mapper.updateAppStatus(id, environment(), before.version(), enabled, tokenRevision, actor, now(clock)));
        AppRow after = requireApp(id, false);
        log.info("开放 API 应用启停状态已写入，requestId={} appId={} enabled={} version={}",
                AuditParticipants.currentRequestId(), id, after.enabled(), after.version());
        return Mutation.changed(appView(after), audit.app(before, after));
    }

    @Transactional(readOnly = true)
    public Page<OwnerOption> owners(String keyword, Pagination pagination) {
        String search = contains(keyword);
        return page(mapper.pageOwners(search, pagination), mapper.countOwners(search));
    }

    private Integer patchOwner(Integer before, PatchApp request) {
        if (!request.isOwnerUserIdSupplied() || Objects.equals(before, request.getOwnerUserId())) {
            return before;
        }
        return checkedOwner(request.getOwnerUserId());
    }

    private Integer checkedOwner(Integer id) {
        if (id != null && mapper.lockEnabledOwner(id) == null) {
            throw OpenApiAdminFailure.invalid("负责人必须是当前启用的用户");
        }
        return id;
    }

    @Transactional(readOnly = true)
    public Page<Call> calls(long appId, Instant from, Instant to, Long endpointId, String requestId,
                            CallResult result, Pagination pagination) {
        requireApp(appId, false);
        CallFilter filter = callFilter(appId, from, to, endpointId, requestId, result, pagination);
        return page(mapper.pageCalls(filter).stream().map(OpenApiAdminService::callView).toList(), mapper.countCalls(filter));
    }

    private CallFilter callFilter(long appId, Instant from, Instant to, Long endpointId, String requestId,
                                  CallResult result, Pagination pagination) {
        checkDatabaseTime(from);
        checkDatabaseTime(to);
        Instant end = to;
        if (end == null) {
            end = from == null ? clock.instant() : from.plus(DEFAULT_CALL_RANGE);
        }
        Instant start = from == null ? end.minus(DEFAULT_CALL_RANGE) : from;
        Duration span = Duration.between(start, end);
        if (span.isNegative() || span.isZero() || span.compareTo(MAX_CALL_RANGE) > 0) {
            throw OpenApiAdminFailure.invalid("查询时间范围必须大于零且不超过 31 天");
        }
        checkDatabaseTime(start);
        checkDatabaseTime(end);
        return new CallFilter(appId, utc(start), utc(end), endpointId, requestId, statusFloor(result), pagination);
    }

    private static void checkDatabaseTime(Instant value) {
        if (value != null && (value.isBefore(FIRST_DATABASE_TIME) || value.isAfter(LAST_DATABASE_TIME))) {
            throw OpenApiAdminFailure.invalid("查询时间必须在 1000 至 9999 年之间");
        }
    }

    private static Integer statusFloor(CallResult result) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case SUCCESS -> 200;
            case DENIED -> 400;
            case FAILED -> 500;
        };
    }

    private static Call callView(CallRow row) {
        CallResult result = switch (row.httpStatus() / 100) {
            case 2 -> CallResult.SUCCESS;
            case 4 -> CallResult.DENIED;
            case 5 -> CallResult.FAILED;
            default -> null;
        };
        return new Call(row.id(), row.requestId(), instant(row.occurredAt()), row.durationMs(), row.httpStatus(),
                row.endpointId(), row.endpointCode(), row.errorCode(), row.sourceIp(), result);
    }

    AppRow requireApp(long id, boolean lock) {
        AppRow row = mapper.app(id, environment(), lock);
        if (row == null) {
            throw OpenApiAdminFailure.notFound();
        }
        return row;
    }

    EndpointRow requireEndpoint(long id, boolean lock) {
        EndpointRow row = mapper.endpoint(id, environment(), lock);
        if (row == null) {
            throw OpenApiAdminFailure.notFound();
        }
        return row;
    }

    private String environment() {
        return properties.environment();
    }

    private static String name(String value) {
        if (!StringUtils.hasText(value)) {
            throw OpenApiAdminFailure.invalid("应用名称不能为空");
        }
        return value.strip();
    }

    private static String contains(String value) {
        return StringUtils.hasText(value) ? containsLiteral(value.strip().toLowerCase(Locale.ROOT)) : null;
    }

    private static String containsLiteral(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static App appView(AppRow row) {
        return new App(row.id(), row.clientId(), row.name(), row.environment(), row.enabled(),
                row.ownerDisplayName() == null ? null : row.ownerUserId(), row.ownerDisplayName(), row.description(),
                row.rateLimitCount(), row.rateLimitWindowSeconds(), row.version(), instant(row.createdAt()), instant(row.updatedAt()));
    }

    Endpoint endpointView(EndpointRow row) {
        return new Endpoint(row.id(), row.endpointCode(), row.moduleCode(), row.displayName(), row.httpMethod(),
                row.pathTemplate(), row.apiVersion(), NO_DATA_SCOPE, row.status(), row.version(),
                OpenApiAdminRoutes.ROOT + OpenApiAdminRoutes.DOCS_V1,
                row.authorizedCount(), sourceInfo(row));
    }

    private EndpointSourceInfo sourceInfo(EndpointRow row) {
        if (row.sourcePath() == null) {
            return null;
        }
        var contract = managed.readContract(row.publicContractJson());
        return new EndpointSourceInfo(row.sourcePath(), registered(row),
                contract == null ? List.of() : contract.parameters(), contract == null ? List.of() : contract.fields(),
                contract != null && contract.collection(), contract != null && contract.page(),
                contract != null && contract.wholeResponse());
    }
}
