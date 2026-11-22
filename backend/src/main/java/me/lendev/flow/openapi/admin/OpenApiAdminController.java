package me.lendev.flow.openapi.admin;

import static me.lendev.flow.common.auth.PermissionCode.*;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.actor;
import static me.lendev.flow.openapi.admin.OpenApiAdminAccess.permit;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.Pagination;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 适配员工管理接口，所有子权限同时要求开放 API 查看权限。
 */
@RestController
@RequestMapping(OpenApiAdminRoutes.ROOT)
@RequiredArgsConstructor
public class OpenApiAdminController {

    private static final int MAX_PAGE_SIZE = 200;

    private final OpenApiAdminService service;
    private final OpenApiAdminGrants grants;

    /**
     * 按模块、发布状态和关键词分页查询接口目录。
     *
     * @param moduleCode 可选模块编码
     * @param status     可选发布状态
     * @param keyword    可选的接口检索词
     * @param pageNo     页码，从 1 开始，默认 1
     * @param pageSize   每页数量，默认 20，最多 200
     * @return 符合条件的接口目录页和总数
     */
    @GetMapping("/endpoints")
    public R<Page<Endpoint>> endpoints(@RequestParam(required = false) @Size(max = 64) String moduleCode,
                                       @RequestParam(required = false) EndpointStatus status,
                                       @RequestParam(required = false) @Size(max = 256) String keyword,
                                       @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int pageSize) {
        permit(OPEN_API_VIEW);
        return R.ok(service.endpoints(moduleCode, status, keyword, pagination(pageNo, pageSize)));
    }

    /**
     * 读取接口登记、发布状态和调用契约。
     *
     * @param endpointId 接口主键
     * @return 指定接口的当前详情
     */
    @GetMapping("/endpoints/{endpointId}")
    public R<Endpoint> endpoint(@PathVariable @Positive long endpointId) {
        permit(OPEN_API_VIEW);
        return R.ok(service.endpoint(endpointId));
    }

    /**
     * 分页查询关联指定接口的应用。
     *
     * @param endpointId 接口主键
     * @param pageNo     页码，从 1 开始，默认 1
     * @param pageSize   每页数量，默认 20，最多 200
     * @return 关联应用的当前页和总数
     */
    @GetMapping("/endpoints/{endpointId}/apps")
    public R<Page<App>> endpointApps(@PathVariable @Positive long endpointId,
                                     @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int pageSize) {
        permit(OPEN_API_VIEW);
        return R.ok(service.endpointApps(endpointId, pagination(pageNo, pageSize)));
    }

    /**
     * 根据接口版本更新发布状态。
     *
     * @param endpointId 待更新的接口主键
     * @param request    预期版本及目标发布状态
     * @return 更新后的接口登记和版本
     */
    @PutMapping("/endpoints/{endpointId}/status")
    public R<Endpoint> updateEndpoint(@PathVariable @Positive long endpointId, @Valid @RequestBody EndpointUpdate request) {
        permit(OPEN_API_ENDPOINT_PUBLISH);
        return R.ok(service.updateEndpoint(endpointId, request, actor()).response());
    }

    /**
     * 分页查询满足环境、启停状态与关键词条件的应用。
     *
     * @param keyword     可选的应用检索词
     * @param environment 可选的应用所属环境
     * @param enabled     可选的启用状态
     * @param pageNo      页码，从 1 开始，默认 1
     * @param pageSize    每页数量，默认 20，最多 200
     * @return 应用列表当前页和总数
     */
    @GetMapping("/apps")
    public R<Page<App>> apps(@RequestParam(required = false) @Size(max = 128) String keyword,
                             @RequestParam(required = false) @Size(max = 32) String environment,
                             @RequestParam(required = false) Boolean enabled,
                             @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int pageSize) {
        permit(OPEN_API_VIEW);
        return R.ok(service.apps(keyword, environment, enabled, pagination(pageNo, pageSize)));
    }

    /**
     * 查询指定应用的注册与限流配置。
     *
     * @param appId 应用主键
     * @return 应用信息、当前状态及版本
     */
    @GetMapping("/apps/{appId}")
    public R<App> app(@PathVariable @Positive long appId) {
        permit(OPEN_API_VIEW);
        return R.ok(service.app(appId));
    }

    /**
     * 在当前环境登记调用应用。
     *
     * @param request 应用名称、可选负责人、用途与限流配置
     * @return 登记后的应用及其客户端标识
     */
    @PostMapping("/apps")
    public R<App> createApp(@Valid @RequestBody CreateApp request) {
        permit(OPEN_API_APP_MANAGE);
        return R.ok(service.createApp(request, actor()).response());
    }

    /**
     * 按版本修改应用配置。省略的字段保留原配置。
     *
     * @param appId   待修改应用的主键
     * @param request 预期版本与待修改字段
     * @return 更新后的应用配置
     */
    @PatchMapping("/apps/{appId}")
    public R<App> patchApp(@PathVariable @Positive long appId, @Valid @RequestBody PatchApp request) {
        permit(OPEN_API_APP_MANAGE);
        return R.ok(service.patchApp(appId, request, actor()).response());
    }

    /**
     * 按应用版本切换启停状态。
     *
     * @param appId   待修改应用的主键
     * @param request 目标启用状态及期望版本
     * @return 更新后的应用和版本
     */
    @PutMapping("/apps/{appId}/status")
    public R<App> updateAppStatus(@PathVariable @Positive long appId, @Valid @RequestBody AppStatus request) {
        permit(OPEN_API_APP_MANAGE);
        return R.ok(service.updateAppStatus(appId, request, actor()).response());
    }

    /**
     * 分页查找可登记为应用负责人的账号。
     *
     * @param keyword  可选的负责人检索词
     * @param pageNo   页码，从 1 开始，默认 1
     * @param pageSize 每页数量，默认 20，最多 200
     * @return 候选账号的当前页和总数
     */
    @GetMapping("/owner-options")
    public R<Page<OwnerOption>> owners(@RequestParam(required = false) @Size(max = 128) String keyword,
                                       @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int pageSize) {
        permit(OPEN_API_APP_MANAGE);
        return R.ok(service.owners(keyword, pagination(pageNo, pageSize)));
    }

    /**
     * 保留历史的数据范围选项路由，接口级授权的选项列表恒为空。
     */
    @GetMapping("/scope-options")
    public R<List<ScopeOption>> scopeOptions(@RequestParam ScopeType type) {
        permit(OPEN_API_GRANT_MANAGE);
        return R.ok(List.of());
    }

    /**
     * 查看指定应用的接口授权记录。
     *
     * @param appId 应用主键
     * @return 已配置的授权关系及其状态
     */
    @GetMapping("/apps/{appId}/grants")
    public R<List<Grant>> grants(@PathVariable @Positive long appId) {
        permit(OPEN_API_VIEW);
        return R.ok(grants.grants(appId));
    }

    /**
     * 按当前授权版本登记、修改或撤销应用的接口授权。
     *
     * @param appId      应用主键
     * @param endpointId 目标接口主键
     * @param request    目标授权状态、接口限流设置和可选的当前版本
     * @return 生效后的授权关系
     */
    @PutMapping("/apps/{appId}/grants/{endpointId}")
    public R<Grant> updateGrant(@PathVariable @Positive long appId, @PathVariable @Positive long endpointId,
                                @Valid @RequestBody GrantUpdate request) {
        permit(OPEN_API_GRANT_MANAGE);
        return R.ok(grants.updateGrant(appId, endpointId, request, actor()).response());
    }

    /**
     * 分页检索指定应用的调用记录。
     *
     * @param appId      应用主键
     * @param from       可选的起始时点
     * @param to         可选的结束时点
     * @param endpointId 可选的接口主键
     * @param requestId  可选的调用关联 ID
     * @param result     可选的调用结果分类
     * @param pageNo     页码，从 1 开始，默认 1
     * @param pageSize   每页数量，默认 20，最多 200
     * @return 访问记录的当前页及匹配总数
     */
    @GetMapping("/apps/{appId}/calls")
    public R<Page<Call>> calls(@PathVariable @Positive long appId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                               @RequestParam(required = false) @Positive Long endpointId,
                               @RequestParam(required = false) @Size(max = 64) String requestId,
                               @RequestParam(required = false) CallResult result,
                               @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int pageSize) {
        permit(OPEN_API_CALL_LOG_VIEW);
        return R.ok(service.calls(appId, from, to, endpointId, requestId, result, pagination(pageNo, pageSize)));
    }

    private static Pagination pagination(int pageNo, int pageSize) {
        return new Pagination((pageNo - 1L) * pageSize, pageSize);
    }

}
