package me.lendev.flow.pms.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.lendev.flow.common.auth.Roles;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.convert.PermissionConverter;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.request.CreatePermissionRequest;
import me.lendev.flow.pms.domain.request.SortPermissionsRequest;
import me.lendev.flow.pms.domain.request.UpdatePermissionRequest;
import me.lendev.flow.pms.service.PermissionService;
import cn.hutool.core.lang.tree.Tree;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限Controller
 *
 * @author lendev
 */
@RestController
@RequestMapping("/permission")
@RequiredArgsConstructor
@Tag(name = "权限")
public class PermissionController {


    private final PermissionService permissionService;

    private final PermissionConverter permissionConverter;

    /**
     * 新建权限
     *
     * @param request 创建权限请求
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PostMapping
    @Operation(summary = "新增权限")
    public R<Void> create(@RequestBody @Validated CreatePermissionRequest request) {
        permissionService.create(request);
        return R.ok();
    }


    /**
     * 批量创建权限
     *
     * @param request 创建权限请求列表，单次最多500条
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PostMapping("/batch")
    @Operation(summary = "批量新增权限")
    public R<Void> batchCreate(@RequestBody @Size(max = 500) List<@NotNull @Valid CreatePermissionRequest> request) {
        permissionService.createBatch(request);
        return R.ok();
    }

    /**
     * 获取所有权限
     *
     * @return R
     */
    @GetMapping
    @Operation(summary = "获取所有权限")
    public R<List<PermissionDto>> findAll() {
        List<PermissionDto> menu = permissionService.findAllMenu();
        return R.ok(menu);
    }

    /**
     * 获取所有权限树
     *
     * @return R
     */
    @GetMapping("/tree")
    @Operation(summary = "获取所有权限树")
    public R<List<Tree<Long>>> findAllTree() {
        List<Tree<Long>> tree = permissionService.findAllTree();
        return R.ok(tree);
    }

    /**
     * 获取菜单树
     *
     * @return R
     */
    @GetMapping("menu/tree")
    @Operation(summary = "获取菜单树")
    public R<List<Tree<Long>>> findMenuTree() {
        List<Tree<Long>> tree = permissionService.findAllMenuTree();
        return R.ok(tree);
    }

    /**
     * 根据id获取
     *
     * @param id 权限ID
     * @return R
     */
    @GetMapping("{id}")
    @Operation(summary = "根据id获取")
    public R<PermissionDto> findOne(@PathVariable Long id) {
        PermissionDto permissionDto = permissionConverter.toPermissionDto(permissionService.getById(id));
        return R.ok(permissionDto);
    }

    /**
     * 根据id更新
     *
     * @param id      权限ID
     * @param request 更新权限请求
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PatchMapping("{id}")
    @Operation(summary = "根据id更新")
    public R<Object> update(@PathVariable Long id, @RequestBody @Validated UpdatePermissionRequest request) {
        permissionService.updatePermission(id, request);
        return R.ok();
    }

    /**
     * 按提交顺序重排同一父级下的资源。
     *
     * @param request 父菜单与排序后的完整资源 ID 列表
     * @return 排序结果
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PatchMapping("/sort")
    @Operation(summary = "调整资源排序")
    public R<Void> sort(@RequestBody @Validated SortPermissionsRequest request) {
        permissionService.sortPermissions(request.parentId(), request.ids());
        return R.ok();
    }

    /**
     * 根据id删除
     *
     * @param id 权限ID
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @DeleteMapping("{id}")
    @Operation(summary = "根据id删除")
    public R<Object> remove(@PathVariable Long id) {
        permissionService.removePermission(id);
        return R.ok();
    }


    /**
     * 根据父id获取按钮/接口权限列表
     *
     * @param parentId 父级菜单权限ID
     * @return R
     */
    @GetMapping("/button/{parentId}")
    @Operation(summary = "根据父id获取权限列表")
    public R<List<PermissionDto>> findButtonAndApi(@PathVariable Long parentId) {
        List<PermissionDto> permissions = permissionService.findButton(parentId)
                .stream().map(permissionConverter::toPermissionDto)
                .toList();
        return R.ok(permissions);
    }

    /**
     * 校验 path 存不存在menu资源内
     *
     * @param path 待校验的菜单路由路径
     * @return R
     */
    @GetMapping("/menu/validate")
    @Operation(summary = "校验path存不存在menu资源内")
    public R<Object> validateMenuPath(String path) {
        boolean b = permissionService.validateMenuPath(path);
        return R.ok(b);
    }

}
