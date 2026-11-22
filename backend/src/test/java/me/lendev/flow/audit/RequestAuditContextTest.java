package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

import me.lendev.flow.common.auth.SaTokenConfigure;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class RequestAuditContextTest extends ApiTestBase {

    @Autowired
    RequestAuditContext context;

    @Test
    void loginClaimRemainsAnonymousAndDoesNotRetainCredentialsOrUntrustedHeaders() {
        var request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "untrusted");
        request.addHeader("X-Request-ID", "untrusted-token");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/auth/login");
        var response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        try {
            LoginRequest login = new LoginRequest();
            login.setUsername("claimed-user");
            login.setPassword("never-save-password");
            login.setCaptchaKey("never-save-challenge");
            login.setCaptcha("1234");
            var scope = context.start(AuditOperation.LOGIN, new Object[]{login});
            assertEquals("ANONYMOUS", scope.actor().type());
            assertNull(scope.actor().account());
            assertEquals("claimed-user", scope.claimedAccount());
            login.setUsername("mutated");
            assertEquals("claimed-user", scope.claimedAccount());
            assertEquals("127.0.0.1", scope.sourceIp());
            assertEquals("/auth/login", scope.requestPath());
            assertEquals(scope.requestId(), response.getHeader("X-Request-ID"));
            assertNotEquals("untrusted-token", scope.requestId());
            assertFalse(objectMapper.writeValueAsString(scope).contains("never-save"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void authenticatedActorAndReferencesAreFrozenBeforeLogout() {
        StpUtil.login(1L);
        StpUtil.getTokenSession().set(SaTokenConfigure.CURRENT_ROLE_KEY, "SUPER_ADMIN");
        var scope = context.start(AuditOperation.PARAM_ITEM_UPDATE, new Object[]{4L, 12L});
        StpUtil.logout();
        assertEquals("USER", scope.actor().type());
        assertEquals("1", scope.actor().id());
        assertEquals("admin", scope.actor().account());
        assertEquals("Admin", scope.actor().name());
        assertEquals("SUPER_ADMIN", scope.actor().roleCode());
        assertEquals("12", scope.targets().getFirst().objectId());
        assertEquals("4", scope.targets().getFirst().parentId());
    }

    @Test
    void notificationGroupFailuresUseOnlyPositiveGroupIds() {
        for (var operation : List.of(AuditOperation.NOTIFICATION_GROUP_UPDATE, AuditOperation.NOTIFICATION_GROUP_STATUS_UPDATE)) {
            var targets = context.start(operation, new Object[]{42L}).targets();
            assertEquals(1, targets.size());
            assertEquals("NOTIFICATION_GROUP", targets.getFirst().objectType());
            assertEquals("42", targets.getFirst().objectId());
            assertTrue(context.start(operation, new Object[]{0L}).targets().isEmpty());
        }
        assertTrue(context.start(AuditOperation.NOTIFICATION_GROUP_CREATE, new Object[0]).targets().isEmpty());
    }

    @Test
    void notificationSceneFailuresRetainOnlyWellFormedSceneCodes() {
        var operation = AuditOperation.NOTIFICATION_SCENE_GROUPS_REPLACE;
        var targets = context.start(operation, new Object[]{"finance.reminder_2"}).targets();
        assertEquals(1, targets.size());
        var target = targets.getFirst();
        assertEquals("NOTIFICATION_SCENE", target.objectType());
        assertEquals("finance.reminder_2", target.objectId());
        assertEquals(target.objectId(), target.objectCode());
        String longest = "a".repeat(64);
        assertEquals(longest, context.start(operation, new Object[]{longest}).targets().getFirst().objectId());
        for (String invalid : List.of("Finance.reminder", "9reminder", " finance.reminder", "finance\nreminder", "a".repeat(65))) {
            assertTrue(context.start(operation, new Object[]{invalid}).targets().isEmpty());
        }
    }
}
