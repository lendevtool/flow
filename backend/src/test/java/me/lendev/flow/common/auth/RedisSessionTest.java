package me.lendev.flow.common.auth;

import static org.junit.jupiter.api.Assertions.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import cn.dev33.satoken.servlet.util.SaTokenContextJakartaServletUtil;
import cn.dev33.satoken.stp.StpUtil;

import java.time.Duration;
import java.util.Map;

import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RedisSessionTest extends ApiTestBase {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private StpInterfaceImpl authorization;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SaTokenContextJakartaServletUtil.clearContext();
    }

    private void bind(String token) {
        SaTokenContextJakartaServletUtil.setContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        StpUtil.setTokenValueToStorage(token);
    }

    @Test
    void loginIsPersistedAndCanBeReadByAnotherRedisAdapter() throws Exception {
        String token = loginAsAdmin();
        assertInstanceOf(SaTokenDaoForRedisTemplate.class, SaManager.getSaTokenDao());
        var other = new SaTokenDaoForRedisTemplate();
        other.init(redis.getConnectionFactory());
        assertEquals("1", other.get("Authorization:login:token:" + token));
        var session = other.getSession("Authorization:login:token-session:" + token);
        assertEquals("SUPER_ADMIN", session.getString("currentRoleCode"));
        assertTrue(redis.getExpire("Authorization:login:token:" + token) > 28700);
        assertTrue(redis.getExpire("Authorization:login:token-session:" + token) > 28700);
        data(postJson("/auth/logout", token, Map.of()));
        assertNull(other.get("Authorization:login:token:" + token));
        assertNull(other.getSession("Authorization:login:token-session:" + token));
    }

    @Test
    void refreshKeepsTokenAndRoleWithoutExtendingTheirAbsoluteTtl() throws Exception {
        String token = loginAsAdmin();
        redis.expire("Authorization:login:token:" + token, Duration.ofSeconds(30));
        redis.expire("Authorization:login:token-session:" + token, Duration.ofSeconds(30));
        data(getJson("/auth/refresh/token", token));
        assertTrue(redis.getExpire("Authorization:login:token:" + token) <= 30);
        assertTrue(redis.getExpire("Authorization:login:token-session:" + token) <= 30);
        assertEquals("SUPER_ADMIN", data(getJson("/user/detail", token)).get("currentRole").get("code").asString());
    }

    @Test
    void expiredTokenCannotAccessProtectedEndpoints() throws Exception {
        String token = loginAsAdmin();
        redis.expire("Authorization:login:token:" + token, Duration.ZERO);
        body(getJson("/user/detail", token), 401);
    }

    @Test
    void permissionsFollowEnabledAssignmentsAndTheSelectedRole() throws Exception {
        String token = loginAsAdmin();
        bind(token);
        assertTrue(authorization.getPermissionList(1L, "login").contains("AddUser"));
        assertTrue(authorization.getRoleList(2L, "login").isEmpty());
        assertTrue(authorization.getRoleList(1L, "other").isEmpty());
        data(postJson("/auth/current-role/switch/ROLE_QA", token, Map.of()));
        bind(token);
        assertFalse(authorization.getPermissionList(1L, "login").contains("AddUser"));
        assertTrue(authorization.getPermissionList(1L, "login").contains("RoleMgt"));
        jdbc.update("update permission set enable=0 where code='RoleMgt'");
        assertFalse(authorization.getPermissionList(1L, "login").contains("RoleMgt"));
        jdbc.update("delete from user_roles_role where userId=1 and roleId=2");
        assertTrue(authorization.getRoleList(1L, "login").isEmpty());
        assertTrue(authorization.getPermissionList(1L, "login").isEmpty());
    }
}
