package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;

import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.bootstrap.AdminBootstrap;
import me.lendev.flow.common.auth.bootstrap.AdminBootstrapProperties;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.mapper.ProfileMapper;
import me.lendev.flow.pms.mapper.RoleMapper;
import me.lendev.flow.pms.mapper.UserMapper;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserService;
import org.junit.jupiter.api.Test;

/**
 * 管理员初始化审计测试：不使用任何测试扩展， 因为 bootstrap 运行在非 HTTP 线程上，必须自行提供 SYSTEM 审计身份，且结束后不得泄漏。
 */
class AdminBootstrapAuditTest {

    private final UserService users = mock(UserService.class);
    private final RequestAuditContext context = new RequestAuditContext(
            mock(UserMapper.class), mock(ProfileMapper.class), mock(RoleMapper.class), Clock.systemUTC());

    @Test
    void bootstrapProvidesSystemIdentityWithoutAnExistingContext() {
        assertNull(AuditSystemContext.actor());
        // 在 register 调用内部断言：审计上下文启动时必须拿到 bootstrap 注入的 SYSTEM 身份，
        // 否则新建管理员这类启动期操作将无法归属操作人。
        doAnswer(invocation -> {
            var scope = context.start(AuditOperation.USER_CREATE, invocation.getArguments());
            assertEquals("SYSTEM", scope.actor().type());
            assertEquals("初始化超级管理员", scope.actor().name());
            assertEquals("SYSTEM", scope.sourceType());
            return null;
        }).when(users).register(any());

        bootstrap().run(null);

        verify(users).register(any());
        // 运行结束后身份作用域必须关闭，避免 SYSTEM 身份泄漏到同线程的后续任务。
        assertNull(AuditSystemContext.actor());
    }

    @Test
    void failedRegistrationDoesNotLeakSystemIdentity() {
        var failure = new IllegalStateException("registration failed");
        // 注册抛异常时身份仍然存在；关键是异常传播后作用域同样要被关闭。
        doAnswer(invocation -> {
            assertEquals("SYSTEM", context.currentActor().type());
            throw failure;
        }).when(users).register(any());

        var adminBootstrap = bootstrap();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> adminBootstrap.run(null)));
        // 失败路径不得把 SYSTEM 身份残留在线程上下文中。
        assertNull(AuditSystemContext.actor());
    }

    private AdminBootstrap bootstrap() {
        when(users.count()).thenReturn(0L);
        var roles = mock(RoleService.class);
        var role = new Role();
        role.setId(1L);
        role.setEnable(true);
        when(roles.findByCode(RoleType.SUPER_ADMIN)).thenReturn(role);
        var properties = new AdminBootstrapProperties();
        properties.setPassword("bootstrap_123");
        return new AdminBootstrap(users, roles, properties);
    }
}
