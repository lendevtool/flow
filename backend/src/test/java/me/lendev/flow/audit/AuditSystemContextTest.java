package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import me.lendev.flow.pms.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

class AuditSystemContextTest {

    @Test
    void explicitSystemIdentityIsScopedAndRestoresPreviousIdentity() {
        assertNull(AuditSystemContext.actor());
        try (var first = AuditSystemContext.open("导入任务")) {
            assertEquals("SYSTEM", AuditSystemContext.actor().type());
            try (var second = AuditSystemContext.open("内部任务")) {
                assertEquals("内部任务", AuditSystemContext.actor().name());
            }
            assertEquals("导入任务", AuditSystemContext.actor().name());
        }
        assertNull(AuditSystemContext.actor());
    }

    @Test
    void backgroundInvocationRequiresExplicitIdentityAndDoesNotInventAUser() {
        var previous = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            var context = new RequestAuditContext(mock(UserMapper.class), mock(ProfileMapper.class), mock(RoleMapper.class), Clock.systemUTC());
            assertThrows(IllegalStateException.class, context::currentActor);
            try (var identity = AuditSystemContext.open("后台同步")) {
                var scope = context.start(AuditOperation.USER_UPDATE, new Object[]{5L});
                assertEquals("SYSTEM", scope.actor().type());
                assertEquals("后台同步", scope.actor().name());
                assertEquals("SYSTEM", scope.sourceType());
                assertNull(scope.actor().id());
            }
        } finally {
            RequestContextHolder.setRequestAttributes(previous);
        }
    }
}
