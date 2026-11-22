package me.lendev.flow.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.Test;

class ManagedReadInvocationTest {
    private static final Class<?> CONTROLLER = ManagedReadInvocationTest.class;
    private static final String SOURCE = "/test/source";
    private final OpenApiRequest caller = new OpenApiRequest(1, "test-client");

    @Test
    void bindsApplicationIdentityToOneUseOfTheSelectedSource() throws Exception {
        var result = ManagedReadInvocation.invoke(CONTROLLER, SOURCE, caller, () -> {
            var identity = ManagedReadInvocation.callerFor(CONTROLLER, SOURCE);
            assertThrows(OpenApiException.class, () -> ManagedReadInvocation.callerFor(CONTROLLER, SOURCE));
            return identity;
        });
        assertEquals(caller, result);
        assertNull(ManagedReadInvocation.callerFor(CONTROLLER, SOURCE));
    }

    @Test
    void rejectsMismatchedOrUnusedIdentityAndClearsContextAfterFailure() {
        assertThrows(OpenApiException.class, () -> ManagedReadInvocation.invoke(CONTROLLER, SOURCE, caller,
                () -> ManagedReadInvocation.callerFor(CONTROLLER, "/test/other-source")));
        assertThrows(OpenApiException.class, () -> ManagedReadInvocation.invoke(CONTROLLER, SOURCE, caller, () -> "unused"));
        assertNull(ManagedReadInvocation.callerFor(CONTROLLER, SOURCE));
    }

    @Test
    void rejectsMyBatisWritesDuringModuleReadAndReleasesGuardOnExit() throws Exception {
        var delegate = mock(Executor.class);
        var statement = mock(MappedStatement.class);
        var guarded = (Executor) new ManagedReadWriteGuard().plugin(delegate);
        var failure = assertThrows(OpenApiException.class, () -> ManagedReadInvocation.invoke(CONTROLLER, SOURCE, caller, true,
                () -> {
                    assertEquals(caller, ManagedReadInvocation.callerForModule(CONTROLLER));
                    return guarded.update(statement, null);
                }));
        assertEquals("READ_ONLY_REQUIRED", failure.code());
        verifyNoInteractions(delegate);
        guarded.update(statement, null);
        verify(delegate).update(statement, null);
    }
}
