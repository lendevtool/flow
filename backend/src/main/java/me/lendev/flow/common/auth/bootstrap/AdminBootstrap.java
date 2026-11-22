package me.lendev.flow.common.auth.bootstrap;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.domain.request.RegisterUserProfileRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动时初始化超级管理员账号。
 *
 * <p>仅在数据库为空时执行；绝不修改已有账号的凭据。</p>
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrap implements ApplicationRunner {

    private final UserService users;
    private final RoleService roles;
    private final AdminBootstrapProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments arguments) {
        if (users.count() != 0) {
            return;
        }
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new IllegalStateException("首次启动空数据库必须设置符合密码策略的 BOOTSTRAP_ADMIN_PASSWORD");
        }
        String username = properties.getUsername();
        if (username == null || username.isBlank() || username.length() > 50) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME 必须为1到50个字符");
        }
        var role = roles.findByCode(RoleType.SUPER_ADMIN);
        if (role == null || !Boolean.TRUE.equals(role.getEnable())) {
            throw new IllegalStateException("请先初始化数据库中的超级管理员角色");
        }
        var request = new RegisterUserRequest();
        request.setUsername(username);
        request.setPassword(properties.getPassword());
        request.setBootstrap(true);
        request.setEnable(true);
        request.setRoleIds(List.of(role.getId()));
        var profile = new RegisterUserProfileRequest();
        profile.setNickName("Admin");
        request.setProfile(profile);
        try (var ignored = AuditSystemContext.open("初始化超级管理员")) {
            try {
                users.register(request);
            } catch (BadRequestException exception) {
                throw new IllegalStateException("首次启动空数据库必须设置符合密码策略的 BOOTSTRAP_ADMIN_PASSWORD", exception);
            }
        }
    }
}
