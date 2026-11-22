package me.lendev.flow.pms.controller;

import cn.dev33.satoken.stp.StpUtil;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.PermissionCode;
import me.lendev.flow.pms.domain.dto.PersonDetailsDto;
import me.lendev.flow.common.auth.Roles;
import me.lendev.flow.common.auth.SaTokenConfigure;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.domain.dto.UserDetailDto;
import me.lendev.flow.pms.domain.dto.UserPageDto;
import me.lendev.flow.pms.domain.request.AddUserRolesRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.domain.dto.TemporaryPasswordDto;
import me.lendev.flow.pms.domain.request.UpdateAppearanceRequest;
import me.lendev.flow.pms.domain.request.UpdateProfileRequest;
import me.lendev.flow.pms.domain.request.UpdateUserRequest;
import me.lendev.flow.pms.domain.request.UserPageRequest;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.pms.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 用户Controller
 *
 * @author lendev
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "用户")
public class UserController {

    private final UserService userService;
    private final ProfileService profileService;


    /**
     * 新建用户
     *
     * @param request 用户注册请求
     * @return R
     */
    @PostMapping
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "新增用户")
    public R<TemporaryPasswordDto> create(@RequestBody @Validated RegisterUserRequest request) {
        return R.ok(userService.register(request));
    }


    /**
     * 获取所有
     *
     * @param request 用户分页查询请求
     * @return R
     */
    @GetMapping
    @Roles(RoleType.SUPER_ADMIN)
    @Operation(summary = "获取所有")
    public R<Page<UserPageDto>> findAll(UserPageRequest request) {
        Page<UserPageDto> ret = userService.queryPage(request);
        return R.ok(ret);
    }


    /**
     * 根据id删除
     *
     * @param id 用户ID
     * @return R
     */
    @DeleteMapping("{id}")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "根据id删除")
    public R<Void> remove(@PathVariable Long id) {
        long currentUserId = StpUtil.getLoginIdAsLong();
        if (currentUserId == id) {
            throw BizException.localized(BizResponseCode.ERR_11006, "error.user.delete.self");
        }
        userService.removeUser(id);
        return R.ok();
    }


    /**
     * 根据id更新
     *
     * @param id      用户ID
     * @param request 更新用户请求
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PatchMapping("{id}")
    @Operation(summary = "根据id更新")
    public R<Void> update(@PathVariable Long id, @RequestBody @Validated UpdateUserRequest request) {
        userService.updateById(id, request);
        return R.ok();
    }


    /**
     * 更新资料（仅限本人操作）
     *
     * @param id      用户id
     * @param request 资料更新请求
     * @return R
     */
    @PatchMapping("/profile/{id}")
    @Operation(summary = "更新资料")
    public R<Void> updateProfile(@PathVariable Long id, @RequestBody UpdateProfileRequest request) {
        long currentUserId = StpUtil.getLoginIdAsLong();
        if (currentUserId != id) {
            throw BizException.localized(BizResponseCode.ERR_11004, "error.profile.edit.owner-only");
        }
        userService.updateProfile(id, request);
        return R.ok();
    }

    /**
     * 部分更新当前登录账号的界面偏好。
     *
     * @param request 偏好更新请求
     * @return 更新结果
     */
    @PatchMapping("/profile/appearance")
    @Operation(summary = "更新当前用户的界面偏好")
    public R<Void> updateAppearance(@RequestBody @Validated UpdateAppearanceRequest request) {
        profileService.updateAppearance(StpUtil.getLoginIdAsLong(), request);
        return R.ok();
    }


    /**
     * 用户信息 {@link UserService#detail(Long, String)}
     *
     * @return R
     */
    @GetMapping("/detail")
    @Operation(summary = "用户信息")
    public R<UserDetailDto> detail() {
        long userId = StpUtil.getLoginIdAsLong();
        String roleCode = SaTokenConfigure.requireCurrentRole();
        UserDetailDto detail = userService.detail(userId, roleCode);
        return R.ok(detail);
    }


    /**
     * 查看本人或按组织架构查看权限读取人员名片。
     */
    @GetMapping("/{id}/person")
    @Operation(summary = "查看人员信息")
    public R<PersonDetailsDto> personDetails(@PathVariable Long id) {
        if (StpUtil.getLoginIdAsLong() != id && !StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(PermissionCode.DEPARTMENT_VIEW);
        }
        return R.ok(userService.personDetails(id));
    }

    /**
     * 根据用户名获取
     *
     * @param username 用户名
     * @return R
     */
    @GetMapping("/{username}")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "根据用户名获取")
    public R<Void> findByUsername(@PathVariable String username) {
        throw BizException.localized(BizResponseCode.ERR_11006, "error.endpoint.unimplemented");
    }


    /**
     * 查询用户的profile
     *
     * @param userId 用户ID
     * @return R
     */
    @GetMapping("/profile/{userId}")
    @Operation(summary = "查询用户的profile")
    public R<Void> getUserProfile(@PathVariable Long userId) {
        throw BizException.localized(BizResponseCode.ERR_11006, "error.endpoint.unimplemented");
    }

    /**
     * 添加角色
     *
     * @param userId  用户id
     * @param request 待添加的角色ID集合
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PostMapping("/roles/add/{userId}")
    @Operation(summary = "添加角色")
    public R<Object> addRoles(@PathVariable Long userId, @RequestBody @Validated AddUserRolesRequest request) {
        userService.addRoles(userId, request);
        return R.ok();
    }

    /**
     * 重置密码
     *
     * @param userId 目标用户 ID
     * @return 一次性展示的临时密码
     */
    @PatchMapping("password/reset/{userId}")
    @Roles({RoleType.SUPER_ADMIN})
    @Operation(summary = "重置密码")
    public R<TemporaryPasswordDto> resetPassword(@PathVariable Long userId) {
        return R.ok(userService.resetPassword(userId));
    }
}
