package me.lendev.flow.pms.controller;

import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;

import cn.dev33.satoken.stp.StpUtil;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.Roles;
import me.lendev.flow.common.auth.SaTokenConfigure;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.convert.RoleConverter;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.RoleDto;
import me.lendev.flow.pms.domain.dto.RolePageDto;
import me.lendev.flow.pms.domain.request.AddRolePermissionsRequest;
import me.lendev.flow.pms.domain.request.AddRoleUsersRequest;
import me.lendev.flow.pms.domain.request.CreateRoleRequest;
import me.lendev.flow.pms.domain.request.RemoveRoleUsersRequest;
import me.lendev.flow.pms.domain.request.RolePageRequest;
import me.lendev.flow.pms.domain.request.UpdateRoleRequest;
import me.lendev.flow.pms.service.RoleService;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ObjectUtil;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色Controller
 */
@RestController
@RequestMapping("/role")
@RequiredArgsConstructor
@Tag(name = "角色")
public class RoleController {

    private final RoleService roleService;

    private final RoleConverter roleConverter;

    /**
     * 新建角色
     *
     * @param request 创建角色请求
     * @return R
     */
    @PostMapping
    @Roles(RoleType.SUPER_ADMIN)
    @Operation(summary = "新增角色")
    public R<Void> create(@RequestBody @Validated CreateRoleRequest request) {
        roleService.createRole(request);
        return R.ok();
    }

    /**
     * 获取所有角色
     *
     * @param enable 可选的启用状态过滤条件，为空时不过滤
     * @return R
     */
    @GetMapping
    @Operation(summary = "获取所有角色")
    public R<List<RoleDto>> findAll(
            @RequestParam(value = "enable", required = false) Boolean enable
    ) {
        List<RoleDto> roleDtoList = roleService.queryChain().and(ROLE.ENABLE.eq(enable, ObjectUtil.isNotNull(enable)))
                .list()
                .stream()
                .map(roleConverter::toRoleDto)
                .toList();
        return R.ok(roleDtoList);
    }

    /**
     * 分页
     *
     * @param request 角色分页查询请求
     * @return R
     */
    @GetMapping("/page")
    @Operation(summary = "分页")
    public R<Page<RolePageDto>> findPagination(RolePageRequest request) {
        Page<RolePageDto> ret = roleService.queryPage(request);
        return R.ok(ret);
    }

    /**
     * 查询角色权限
     *
     * @param id 角色ID
     * @return R
     */
    @GetMapping("/permissions")
    @Operation(summary = "查询角色权限")
    public R<List<PermissionDto>> findRolePermissions(Long id) {
        List<PermissionDto> permissionDtoList = roleService.findRolePermissions(id);
        return R.ok(permissionDtoList);
    }


    /**
     * 根据id获取
     *
     * @param id 角色ID
     * @return R
     */
    @GetMapping("{id}")
    @Roles(RoleType.SUPER_ADMIN)
    @Operation(summary = "根据id获取")
    public R<RoleDto> findOne(@PathVariable Long id) {
        RoleDto roleDto = roleConverter.toRoleDto(roleService.getById(id));
        return R.ok(roleDto);
    }


    /**
     * 根据id更新
     *
     * @param id      角色ID
     * @param request 更新角色请求
     * @return R
     */
    @PatchMapping("{id}")
    @Roles({RoleType.SUPER_ADMIN, RoleType.SYS_ADMIN, RoleType.ROLE_PMS})
    @Operation(summary = "根据id更新")
    public R<Void> update(@PathVariable Long id, @RequestBody @Validated UpdateRoleRequest request) {
        // 变更角色权限集合属于高危操作：即使接口允许 SYS_ADMIN/ROLE_PMS 访问，
        // 只要请求携带 permissionIds 就额外收紧为仅 SUPER_ADMIN 可执行。
        if (request.getPermissionIds() != null) {
            StpUtil.checkRole(RoleType.SUPER_ADMIN);
        }
        roleService.updateRole(id, request);
        return R.ok();
    }


    /**
     * 根据id删除
     *
     * @param id 角色ID
     * @return R
     */
    @DeleteMapping("{id}")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "根据id删除")
    public R<Void> remove(@PathVariable Long id) {
        roleService.removeRole(id);
        return R.ok();
    }


    /**
     * 给角色添加权限
     *
     * @param request 角色ID与权限ID集合
     * @return R
     */
    @PostMapping("/permissions/add")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "给角色添加权限")
    public R<Void> addRolePermissions(@RequestBody @Validated AddRolePermissionsRequest request) {
        roleService.addRolePermissions(request);
        return R.ok();
    }

    /**
     * 角色的权限树
     *
     * @return R
     */
    @GetMapping("/permissions/tree")
    @Operation(summary = "角色的权限树")
    public R<List<Tree<Long>>> permissionTree() {
        String roleCode = SaTokenConfigure.requireCurrentRole();
        List<Tree<Long>> treeList = roleService.findRolePermissionsTree(roleCode);
        return R.ok(treeList);
    }


    /**
     * 给角色分配用户
     *
     * @param roleId  角色ID
     * @param request 待分配的用户ID集合
     * @return R
     */
    @PatchMapping("/users/add/{roleId}")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "给角色分配用户")
    public R<Void> addRoleUsers(@PathVariable Long roleId, @RequestBody @Validated AddRoleUsersRequest request) {
        roleService.addRoleUsers(roleId, request);
        return R.ok();
    }


    @PatchMapping("/users/remove/{roleId}")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "移除角色")
    public R<Void> removeRoleUsers(@PathVariable Long roleId, @RequestBody @Validated RemoveRoleUsersRequest request) {
        roleService.removeRoleUsers(roleId, request);
        return R.ok();
    }

}
