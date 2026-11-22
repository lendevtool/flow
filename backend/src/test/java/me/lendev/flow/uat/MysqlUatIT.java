package me.lendev.flow.uat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.support.ApiTestSupport;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import me.lendev.flow.support.SaTokenTestExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 显式选择加入的 UAT 验证（需设置环境变量 RUN_MYSQL_UAT=true）。 绝不导入 H2 建表脚本或测试账号夹具，只针对真实 MySQL UAT 库执行。
 */
@SpringBootTest(properties = "spring.sql.init.mode=never")
@ActiveProfiles("mysql")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(SaTokenTestExtension.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_UAT", matches = "true")
@Import({MysqlUatIT.IsolatedSessions.class, JdbcFixtureConfiguration.class})
@Transactional
class MysqlUatIT extends ApiTestSupport {

    private static final String PREFIX = "flow:uat-it:" + UUID.randomUUID() + ":";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RoleService roles;
    @Autowired
    private PermissionService permissions;
    private final List<String> pendingRedisKeys = new ArrayList<>();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String url = System.getenv("DB_URL");
        if (url == null || !url.matches("jdbc:mysql://localhost:3306/flow_uat(?:\\?.*)?")) {
            throw new IllegalStateException("UAT verification is restricted to localhost:3306/flow_uat");
        }
        if (System.getenv("BOOTSTRAP_ADMIN_PASSWORD") == null) {
            throw new IllegalStateException("Explicit UAT administrator credentials required");
        }
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6379);
        registry.add("spring.data.redis.database", () -> System.getenv().getOrDefault("UAT_REDIS_DATABASE", "15"));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedSessions {

        @Bean
        @Primary
        SaTokenDaoForRedisTemplate uatSessionDao() {
            return new SaTokenDaoForRedisTemplate() {
                @Override
                public String wrapKey(String key) {
                    return PREFIX + key;
                }
            };
        }
    }

    @Override
    protected String loginAsAdmin() throws Exception {
        return login(ADMIN, System.getenv("BOOTSTRAP_ADMIN_PASSWORD"));
    }

    @Override
    protected String login(String username, String password) throws Exception {
        String token = super.login(username, password);
        pendingRedisKeys.add("flow:auth:activity:" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(token));
        return token;
    }

    @AfterEach
    void removeOnlyThisRunsRedisSessions() {
        List<String> keys = new ArrayList<>(pendingRedisKeys);
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createUser(String root, String username, String password, Long roleId) throws Exception {
        var created = data(postJson("/user", root, map("username", username, "roleIds", List.of(roleId))));
        String temporary = created.path("temporaryPassword").asString();
        String restricted = login(username, temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", password)));
        return jdbc.queryForObject("select id from `user` where username=?", Long.class, username);
    }

    @Test
    void mysqlBootstrapCaptchaLoginRefreshAndLogoutWorkTogether() throws Exception {
        assertEquals("flow_uat", jdbc.queryForObject("select database()", String.class));
        assertEquals("MySQL", jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName()));
        var image = mockMvc.perform(get("/auth/captcha")).andExpect(status().isOk()).andReturn().getResponse();
        pendingRedisKeys.add("flow:captcha:" + image.getHeader("X-Captcha-Key"));
        assertEquals("image/png", image.getContentType());
        assertTrue(image.getContentAsByteArray().length > 0);
        String token = loginAsAdmin();
        var detail = data(getJson("/user/detail", token));
        assertEquals("admin", detail.get("username").asString());
        assertEquals("SUPER_ADMIN", detail.get("currentRole").get("code").asString());
        assertFalse(detail.has("password"));
        assertFalse(detail.has("identityKey"));
        assertEquals(token, data(getJson("/auth/refresh/token", token)).get("accessToken").asString());
        data(postJson("/auth/logout", token, Map.of()));
        body(getJson("/user/detail", token), 401);
    }

    @Test
    void mysqlUserRolePermissionWritesRespectBoundariesAndCleanRelationships() throws Exception {
        String root = loginAsAdmin();
        String roleCode = unique("UAT_ROLE_");
        data(postJson("/role", root, map("code", roleCode, "name", roleCode, "permissionIds", List.of(2, 3))));
        Long roleId = roles.findByCode(roleCode).getId();
        String username = unique("uat_");
        Long userId = createUser(root, username, "UatLogin123!", roleId);
        String ordinary = login(username, "UatLogin123!");
        code(getJson("/user", ordinary), 11003);
        data(patchJson("/user/profile/" + userId, ordinary, map("nickName", "UAT")));
        data(getJson("/role/permissions/tree", ordinary));
        body(deleteJson("/permission/3", root), 400);
        String code = unique("UAT_PERM_");
        data(postJson("/permission", root, map("name", code, "code", code, "type", "BUTTON", "parentId", 3)));
        Long permissionId = jdbc.queryForObject("select id from permission where code=?", Long.class, code);
        data(postJson("/role/permissions/add", root, map("id", roleId, "permissionIds", List.of(permissionId))));
        data(deleteJson("/permission/" + permissionId, root));
        assertNull(permissions.getById(permissionId));
        assertEquals(0, jdbc.queryForObject("select count(*) from role_permissions_permission where permissionId=?", Integer.class, permissionId));
        data(deleteJson("/user/" + userId, root));
        body(getJson("/user/detail", ordinary), 401);
        data(deleteJson("/role/" + roleId, root));
    }

    @Test
    void mysqlRoleManagerCannotUsePatchToGrantPermissions() throws Exception {
        String root = loginAsAdmin();
        var role = roles.findByCode("ROLE_PMS");
        if (role == null) {
            data(postJson("/role", root, map("code", "ROLE_PMS", "name", unique("UAT_Manager_"))));
            role = roles.findByCode("ROLE_PMS");
        }
        String username = unique("uatmgr_");
        createUser(root, username, "UatLogin123!", role.getId());
        code(patchJson("/role/" + role.getId(), login(username, "UatLogin123!"), map("permissionIds", List.of(13))), 11003);
    }

    @Test
    void mysqlPasswordResetAndChangeInvalidateEveryOldSession() throws Exception {
        String root = loginAsAdmin();
        String username = unique("uatpw_");
        Long id = createUser(root, username, "OldUatTest123!", 2L);
        String first = login(username, "OldUatTest123!");
        String second = login(username, "OldUatTest123!");
        String temporary = data(patchJson("/user/password/reset/" + id, root, null)).path("temporaryPassword").asString();
        body(getJson("/user/detail", first), 401);
        body(getJson("/user/detail", second), 401);
        String replacement = login(username, temporary);
        data(postJson("/auth/password", replacement, map("oldPassword", temporary, "newPassword", "FinalUatTest123!")));
        body(getJson("/user/detail", replacement), 401);
        data(getJson("/user/detail", login(username, "FinalUatTest123!")));
    }

    @Test
    void mysqlForeignKeysAndIndependentUniquenessConstraintsAreEnforced() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into user_roles_role(userId,roleId) values (2147483000,1)"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into role_permissions_permission(roleId,permissionId) values (1,2147483000)"));
        String duplicateRoleCode = unique("UAT_DUP_");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into role(code,name) values (?, '超级管理员')", duplicateRoleCode));
        String duplicateRoleName = unique("UAT_DUP_");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into role(code,name) values ('SUPER_ADMIN', ?)", duplicateRoleName));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("delete from permission where id=3"));
        String usernameWithoutIdentity = unique("uatnull_");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into `user`(username,password,identityKey) values (?, 'not-a-credential', null)", usernameWithoutIdentity));
    }

    @Test
    void mysqlUsesExactUsernameMatchingAndConsumesUnknownAccountCaptcha() throws Exception {
        var captcha = captchaService.create();
        code(postJson("/auth/login", map("username", "ADMIN", "password", System.getenv("BOOTSTRAP_ADMIN_PASSWORD"),
                "captchaKey", captcha.getKey(), "captcha", captcha.getValue().getCode())), 10002);
        assertFalse(captchaService.verify(captcha.getKey(), captcha.getValue().getCode()));
    }
}
