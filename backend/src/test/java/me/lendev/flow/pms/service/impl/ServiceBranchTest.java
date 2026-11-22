package me.lendev.flow.pms.service.impl;

import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;
import cn.hutool.core.lang.tree.Tree;

import java.util.List;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.domain.dto.LoginTokenDto;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.RoleDto;
import me.lendev.flow.pms.domain.dto.UserDetailDto;
import me.lendev.flow.pms.domain.dto.UserPageDto;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.domain.request.UpdateUserRequest;
import me.lendev.flow.pms.domain.request.UserPageRequest;
import me.lendev.flow.pms.service.CaptchaService;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import me.lendev.flow.support.SaTokenTestExtension;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 服务层分支测试
 */
@ExtendWith(SaTokenTestExtension.class)
class ServiceBranchTest extends ApiTestBase {

    @Autowired
    private UserService userService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private CaptchaService captchaService;

    @Test
    void loginWithUnknownUser() {
        LoginRequest request = loginRequest("nobody", "whatever");
        BizException exception = assertThrows(BizException.class, () -> userService.login(request));
        assertEquals(BizResponseCode.ERR_10002, exception.getCode());
    }

    @Test
    void loginWithoutCaptcha() {
        LoginRequest request = new LoginRequest();
        request.setUsername(ADMIN);
        request.setPassword(ADMIN_PASSWORD);
        BizException exception = assertThrows(BizException.class, () -> userService.login(request));
        assertEquals(BizResponseCode.ERR_10003, exception.getCode());
    }

    @Test
    void loginWithWrongPassword() {
        LoginRequest request = loginRequest("wrong");
        BizException exception = assertThrows(BizException.class, () -> userService.login(request));
        assertEquals(BizResponseCode.ERR_10002, exception.getCode());
    }

    @Test
    void loginWithCaptcha() {
        LoginTokenDto tokenDto = userService.login(loginRequest(ADMIN_PASSWORD));
        assertNotNull(tokenDto.getAccessToken());
    }

    @Test
    void detailWithoutAnyRole() {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername("no_role_user");
        request.setPassword("Fixture1234!");
        userService.register(request);
        User user = userService.queryChain().and(USER.USERNAME.eq("no_role_user")).one();

        Long userId = user.getId();
        BizException exception = assertThrows(BizException.class, () -> userService.detail(userId, ""));
        assertEquals(BizResponseCode.ERR_11005, exception.getCode());
    }

    @Test
    void detailWithUnmatchedRoleCode() {
        UserDetailDto detail = userService.detail(1L, "NOT_EXISTS");
        assertEquals(ADMIN, detail.getUsername());
        assertNull(detail.getCurrentRole());
        assertEquals(2, detail.getRoles().size());
        assertNotNull(detail.getProfile());
    }

    @Test
    void detailWithMatchedRoleCode() {
        UserDetailDto detail = userService.detail(1L, "ROLE_QA");
        assertEquals("ROLE_QA", detail.getCurrentRole().getCode());
        List<RoleDto> roles = detail.getRoles();
        assertTrue(roles.stream().allMatch(RoleDto::getEnable));
    }

    @Test
    void switchRole() {
        userService.login(loginRequest(ADMIN_PASSWORD));
        assertNotNull(userService.switchRole(1L, "ROLE_QA").getAccessToken());
        BizException exception = assertThrows(BizException.class, () -> userService.switchRole(1L, "NOPE"));
        assertEquals(BizResponseCode.ERR_11005, exception.getCode());
    }

    @Test
    void registerDuplicatedUser() {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername("dup_register");
        request.setPassword("Fixture1234!");
        userService.register(request);

        BizException exception = assertThrows(BizException.class, () -> userService.register(request));
        assertEquals(BizResponseCode.ERR_10001, exception.getCode());
    }

    @Test
    void removeRootUserIsRejected() {
        BizException exception = assertThrows(BizException.class, () -> userService.removeUser(1L));
        assertEquals(BizResponseCode.ERR_11006, exception.getCode());
    }

    @Test
    void updateUserWithoutAnyField() {
        userService.updateById(1L, new UpdateUserRequest());
        assertTrue(userService.getById(1L).getEnable());
    }

    @Test
    void queryUserPage() {
        UserPageRequest request = new UserPageRequest();
        request.setUsername(ADMIN);
        request.setEnable(true);
        Page<UserPageDto> page = userService.queryPage(request);
        assertEquals(1L, page.getTotal());
        assertEquals(ADMIN, page.getPageData().getFirst().getUsername());
        assertEquals(2, page.getPageData().getFirst().getRoles().size());
    }

    @Test
    void findRolePermissionsTree() {
        List<Tree<Long>> adminTree = roleService.findRolePermissionsTree("SUPER_ADMIN");
        assertTrue(adminTree.size() > 0);

        List<Tree<Long>> qaTree = roleService.findRolePermissionsTree("ROLE_QA");
        assertTrue(qaTree.size() > 0);

        assertThrows(BadRequestException.class, () -> roleService.findRolePermissionsTree("NOPE"));
    }

    @Test
    void findRolePermissions() {
        List<PermissionDto> permissions = roleService.findRolePermissions(2L);
        assertEquals(11, permissions.size());
        assertNotNull(permissions.getFirst().getCode());
    }

    @Test
    void findByRoleCode() {
        assertEquals("超级管理员", roleService.findByCode("SUPER_ADMIN").getName());
        assertNull(roleService.findByCode("NOPE"));
        assertEquals(2, roleService.findRolesByUserId(1L).size());
    }

    @Test
    void loginWithoutAnyRole() {
        RegisterUserRequest register = new RegisterUserRequest();
        register.setUsername("lonely_user");
        register.setPassword("Fixture1234!");
        userService.register(register);

        assertNotNull(userService.login(loginRequest("lonely_user", "Fixture1234!")).getAccessToken());
    }

    private LoginRequest loginRequest(String password) {
        return loginRequest(ADMIN, password);
    }

    private LoginRequest loginRequest(String username, String password) {
        Pair<String, ICaptcha> captcha = captchaService.create();
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setCaptchaKey(captcha.getKey());
        request.setCaptcha(captcha.getValue().getCode());
        return request;
    }

}
