package me.lendev.flow.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import me.lendev.flow.common.auth.PasswordHashes;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.exception.NotLoginException;
import me.lendev.flow.pms.domain.request.ChangePasswordRequest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserService;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class CredentialRaceTest extends ApiTestBase {

    @Autowired
    private UserService users;
    @MockitoSpyBean
    private RoleService roles;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void loginThatPassedOldPasswordBeforeResetCannotCreateUsableTokenAfterReset() throws Exception {
        var registration = new RegisterUserRequest();
        registration.setUsername("credential_race");
        registration.setPassword("OldTest1234!");
        registration.setRoleIds(List.of(2L));
        users.register(registration);
        Long id = users.list().stream().filter(u -> "credential_race".equals(u.getUsername()))
                .findFirst().orElseThrow().getId();
        var passwordVerified = new CountDownLatch(1);
        var allowTokenCreation = new CountDownLatch(1);
        // 用两个 CountDownLatch 控制竞态时序：登录线程在旧密码校验通过后暂停，
        // 待主线程完成密码重置再放行，验证基于旧凭据签发的 token 不能访问系统。
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("stale-login")) {
                passwordVerified.countDown();
                assertTrue(allowTokenCreation.await(5, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(roles).findRolesByUserId(id);
        var captcha = captchaService.create();
        var request = new LoginRequest();
        request.setUsername("credential_race");
        request.setPassword("OldTest1234!");
        request.setCaptchaKey(captcha.getKey());
        request.setCaptcha(captcha.getValue().getCode());
        try (var worker = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "stale-login"))) {
            var login = worker.submit(() -> {
                SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
                try {
                    return users.login(request).getAccessToken();
                } finally {
                    SaTokenContextJakartaServletUtil.clearContext();
                }
            });
            assertTrue(passwordVerified.await(5, TimeUnit.SECONDS));
            users.resetPassword(id);
            allowTokenCreation.countDown();
            body(getJson("/user/detail", login.get(5, TimeUnit.SECONDS)), 401);
        } finally {
            allowTokenCreation.countDown();
            users.removeUser(id);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void inFlightPasswordChangeCannotOverwriteACompletedAdministratorReset() throws Exception {
        var registration = new RegisterUserRequest();
        registration.setUsername("change_reset_race");
        registration.setPassword("OldTest1234!");
        registration.setRoleIds(List.of(2L));
        users.register(registration);
        var user = users.list().stream().filter(u -> "change_reset_race".equals(u.getUsername()))
                .findFirst().orElseThrow();
        String token = login("change_reset_race", "OldTest1234!");
        var oldPasswordVerified = new CountDownLatch(1);
        var continueChange = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var change = worker.submit(() -> {
                SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
                StpUtil.setTokenValueToStorage(token);
                // 保留真实的哈希校验结果，仅在旧凭据校验通过后暂停线程，制造竞态窗口。
                try (var bcrypt = mockStatic(PasswordHashes.class, CALLS_REAL_METHODS)) {
                    bcrypt.when(() -> PasswordHashes.matches("OldTest1234!", user.getPassword())).thenAnswer(invocation -> {
                        boolean correct = (boolean) invocation.callRealMethod();
                        oldPasswordVerified.countDown();
                        assertTrue(continueChange.await(5, TimeUnit.SECONDS));
                        return correct;
                    });
                    var request = new ChangePasswordRequest();
                    request.setOldPassword("OldTest1234!");
                    request.setNewPassword("OtherTest1234!");
                    try {
                        users.changePassword(request);
                        return false;
                    } catch (NotLoginException expected) {
                        return true;
                    }
                } finally {
                    SaTokenContextJakartaServletUtil.clearContext();
                }
            });
            // 在途改密请求被旧密码校验通过后暂停，此时管理员完成重置；
            // 放行后应因凭据已过期抛出 NotLoginException，且数据库中的密码保持为重置结果。
            assertTrue(oldPasswordVerified.await(5, TimeUnit.SECONDS));
            var reset = users.resetPassword(user.getId());
            continueChange.countDown();
            assertTrue(change.get(5, TimeUnit.SECONDS), "stale credential changes must be rejected");
            assertTrue(PasswordHashes.matches(reset.temporaryPassword(), users.getById(user.getId()).getPassword()));
        } finally {
            continueChange.countDown();
            users.removeUser(user.getId());
        }
    }

}
