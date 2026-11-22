package me.lendev.flow.param;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.PermissionCode;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.param.domain.*;
import me.lendev.flow.param.request.*;
import me.lendev.flow.param.audit.AuditedParamCacheService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * 参数管理接口：提供参数分类与明细的查询、增删改、排序及缓存刷新能力。
 */
@RestController
@RequestMapping("/param")
@RequiredArgsConstructor
@Tag(name = "参数管理")
public class ParamController {

    private final ParamService service;
    private final AuditedParamCacheService cache;

    // 超级管理员直接放行；其他用户需具备参数管理权限，若目标权限与管理权限不同则再校验具体操作权限
    static void permit(String code) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(PermissionCode.PARAM_MGT);
            if (!PermissionCode.PARAM_MGT.equals(code)) {
                StpUtil.checkPermission(code);
            }
        }
    }

    /**
     * 分页查询参数分类，支持按编码、名称或英文名关键字模糊匹配。
     */
    @GetMapping("/groups")
    public R<Page<ParamGroup>> groups(@Validated ParamPageRequest request) {
        permit(PermissionCode.PARAM_MGT);
        return R.ok(service.groups(request));
    }

    /**
     * 查询单个参数分类详情，附带创建人/更新人名称。
     */
    @GetMapping("/groups/{id}")
    public R<ParamGroup> group(@PathVariable Long id) {
        permit(PermissionCode.PARAM_MGT);
        return R.ok(service.group(id));
    }

    /**
     * 查询全部参数分类的排序视图，供拖拽排序界面使用。
     */
    @GetMapping("/groups/order")
    public R<List<ParamGroup>> groupOrder() {
        permit(PermissionCode.PARAM_SORT);
        return R.ok(service.groupOrder());
    }

    /**
     * 重排参数分类顺序；请求的 id 列表必须恰好覆盖全部分类，否则拒绝。
     */
    @PatchMapping("/groups/sort")
    public R<Void> sortGroups(@Validated @RequestBody ParamSortRequest request) {
        permit(PermissionCode.PARAM_SORT);
        service.sortGroups(request);
        return R.ok();
    }

    /**
     * 创建参数分类；编码全局唯一，字段配置由服务端重新校验。
     */
    @PostMapping("/groups")
    public R<ParamGroup> createGroup(@Validated @RequestBody ParamGroupRequest request) {
        permit(PermissionCode.PARAM_CREATE);
        return R.ok(service.createGroup(request));
    }

    /**
     * 更新参数分类；编码不可修改，字段配置变更需兼容存量明细数据。
     */
    @PatchMapping("/groups/{id}")
    public R<ParamGroup> updateGroup(@PathVariable Long id, @Validated @RequestBody ParamGroupRequest request) {
        permit(PermissionCode.PARAM_EDIT);
        return R.ok(service.updateGroup(id, request));
    }

    /**
     * 删除参数分类；分类下仍存在明细或被维度/文档绑定时拒绝删除。
     */
    @DeleteMapping("/groups/{id}")
    public R<Void> deleteGroup(@PathVariable Long id) {
        permit(PermissionCode.PARAM_DELETE);
        service.deleteGroup(id);
        return R.ok();
    }

    /**
     * 分页查询指定分类下的参数明细，支持关键字模糊匹配。
     */
    @GetMapping("/groups/{groupId}/items")
    public R<Page<ParamItem>> items(@PathVariable Long groupId, @Validated ParamPageRequest request) {
        permit(PermissionCode.PARAM_MGT);
        return R.ok(service.items(groupId, request));
    }

    /**
     * 查询单条参数明细；明细不属于该分类时视为不存在。
     */
    @GetMapping("/groups/{groupId}/items/{id}")
    public R<ParamItem> item(@PathVariable Long groupId, @PathVariable Long id) {
        permit(PermissionCode.PARAM_MGT);
        return R.ok(service.item(groupId, id));
    }

    /**
     * 创建参数明细；编码在分类内唯一，缺省值按分类字段配置自动补齐。
     */
    @PostMapping("/groups/{groupId}/items")
    public R<ParamItem> createItem(@PathVariable Long groupId, @Validated @RequestBody ParamItemRequest request) {
        permit(PermissionCode.PARAM_CREATE);
        return R.ok(service.createItem(groupId, request));
    }

    /**
     * 更新参数明细；编码不可修改，槽位类明细会联动校验绑定关系。
     */
    @PatchMapping("/groups/{groupId}/items/{id}")
    public R<ParamItem> updateItem(@PathVariable Long groupId, @PathVariable Long id,
                                   @Validated @RequestBody ParamItemRequest request) {
        permit(PermissionCode.PARAM_EDIT);
        return R.ok(service.updateItem(groupId, id, request));
    }

    /**
     * 删除参数明细；被交易日历、待办、流程或文档引用时拒绝删除。
     */
    @DeleteMapping("/groups/{groupId}/items/{id}")
    public R<Void> deleteItem(@PathVariable Long groupId, @PathVariable Long id) {
        permit(PermissionCode.PARAM_DELETE);
        service.deleteItem(groupId, id);
        return R.ok();
    }

    /**
     * 查询分类下全部明细的排序视图，供拖拽排序界面使用。
     */
    @GetMapping("/groups/{groupId}/items/order")
    public R<List<ParamItem>> order(@PathVariable Long groupId) {
        permit(PermissionCode.PARAM_SORT);
        return R.ok(service.order(groupId));
    }

    /**
     * 重排分类下明细顺序；id 列表必须恰好覆盖该分类全部明细，否则拒绝。
     */
    @PatchMapping("/groups/{groupId}/sort")
    public R<Void> sort(@PathVariable Long groupId, @Validated @RequestBody ParamSortRequest request) {
        permit(PermissionCode.PARAM_SORT);
        service.sort(groupId, request);
        return R.ok();
    }

    /**
     * 按分类编码查询启用明细的选项列表；分类不存在或停用时返回空数组。
     */
    @GetMapping("/options/{code}")
    public R<JsonNode> options(@PathVariable String code) {
        permit(PermissionCode.PARAM_MGT);
        return R.ok(service.options(code));
    }

    /**
     * 手动刷新参数查找缓存，使全部旧缓存立即失效并记录审计。
     */
    @PostMapping("/cache/refresh")
    public R<Void> refresh() {
        permit(PermissionCode.PARAM_REFRESH);
        cache.refresh();
        return R.ok();
    }
}
