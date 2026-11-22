package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

class PreparedAuditExecutorTest {

    private final AuditStore store = mock(AuditStore.class);
    private final AuditContext context = mock(AuditContext.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    private final AuditEvents events = new AuditEvents(clock, new AuditSnapshots(new ObjectMapper()));
    private final PreparedAuditExecutor executor = new PreparedAuditExecutor(store, events, context);
    private final AuditActor user = new AuditActor("USER", "1", "admin", "管理员", "SUPER_ADMIN", "超级管理员");

    private AuditScope scope(AuditActor actor) {
        return AuditScope.builder().operationId(UUID.randomUUID().toString()).startedAt(clock.instant())
                .startedNanos(System.nanoTime()).actor(actor).claimedAccount("claimed")
                .sourceType("API").targets(List.of()).build();
    }

    @Test
    void prepareFailurePreventsBusinessSideEffect() {
        when(store.prepare(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        AtomicInteger calls = new AtomicInteger();
        var logout = scope(user);
        assertThrows(AuditUnavailableException.class, () -> executor.execute(logout, AuditOperation.LOGOUT,
                new Object[0], calls::incrementAndGet));
        assertEquals(0, calls.get());
        verify(store, never()).completePrepared(anyLong(), any());
    }

    @Test
    void successfulLoginUsesAuthenticatedActorAndSameEventIdentity() throws Throwable {
        when(store.prepare(any())).thenReturn(12L);
        when(context.currentActor()).thenReturn(user);
        Object token = new Object();
        assertSame(token, executor.execute(scope(AuditActor.anonymous()), AuditOperation.LOGIN, new Object[0], () -> token));
        ArgumentCaptor<AuditEvent> intent = ArgumentCaptor.forClass(AuditEvent.class);
        ArgumentCaptor<AuditEvent> completed = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).prepare(intent.capture());
        verify(store).completePrepared(eq(12L), completed.capture());
        assertEquals("ANONYMOUS", intent.getValue().actor().type());
        assertEquals("USER", completed.getValue().actor().type());
        assertEquals("SUCCESS", completed.getValue().result());
        assertEquals(intent.getValue().eventId(), completed.getValue().eventId());
        assertEquals(intent.getValue().operationId(), completed.getValue().operationId());
    }

    @Test
    void rejectedLoginIsAnonymousAndOriginalErrorIsPreserved() {
        var error = new BizException(BizResponseCode.ERR_10002);
        var login = scope(AuditActor.anonymous());
        assertSame(error, assertThrows(BizException.class, () -> executor.execute(login,
                AuditOperation.LOGIN, new Object[0], () -> {
                    throw error;
                })));
        var result = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).completePrepared(anyLong(), result.capture());
        assertEquals("FAILURE", result.getValue().result());
        assertEquals("ANONYMOUS", result.getValue().actor().type());
        assertEquals("10002", result.getValue().businessCode());
        verify(context, never()).currentActor();
    }

    @Test
    void confirmationFailurePreservesSuccessAndDoesNotReplayBusiness() throws Throwable {
        doThrow(new DataAccessResourceFailureException("confirmation failed")).when(store).completePrepared(anyLong(), any());
        AtomicInteger calls = new AtomicInteger();
        Object result = executor.execute(scope(user), AuditOperation.PARAM_CACHE_REFRESH, new Object[0], calls::incrementAndGet);
        assertEquals(1, result);
        assertEquals(1, calls.get());
        verify(store).prepare(any());
        verify(store).completePrepared(anyLong(), any());
        verifyNoMoreInteractions(store);
    }

    @Test
    void logoutKeepsBeforeIdentityAndPartialExternalErrorsAreUnknown() throws Throwable {
        executor.execute(scope(user), AuditOperation.LOGOUT, new Object[0], () -> null);
        verify(context, never()).currentActor();
        RuntimeException redisFailure = new DataAccessResourceFailureException("raw-secret-error");
        var refresh = scope(user);
        assertSame(redisFailure, assertThrows(RuntimeException.class, () -> executor.execute(refresh,
                AuditOperation.PARAM_CACHE_REFRESH, new Object[0], () -> {
                    throw redisFailure;
                })));
        var results = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store, times(2)).completePrepared(anyLong(), results.capture());
        assertEquals("1", results.getAllValues().getFirst().actor().id());
        assertEquals("UNKNOWN", results.getValue().result());
        assertFalse(new ObjectMapper().writeValueAsString(results.getValue()).contains("raw-secret-error"));
    }

    @Test
    void identityReadFailureAfterLoginDoesNotTurnSessionSuccessIntoFailure() throws Throwable {
        when(context.currentActor()).thenThrow(new DataAccessResourceFailureException("actor lookup failed"));
        Object token = new Object();
        assertSame(token, executor.execute(scope(AuditActor.anonymous()), AuditOperation.LOGIN, new Object[0], () -> token));
        verify(store).prepare(any());
        verify(store, never()).completePrepared(anyLong(), any());
    }

    @Test
    void redisFailureIsClassifiedWithoutRawInfrastructureMessage() {
        var error = new org.springframework.data.redis.RedisConnectionFailureException("raw-redis-secret");
        var refresh = scope(user);
        assertSame(error, assertThrows(RuntimeException.class, () -> executor.execute(refresh,
                AuditOperation.PARAM_CACHE_REFRESH, new Object[0], () -> {
                    throw error;
                })));
        var result = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).completePrepared(anyLong(), result.capture());
        assertEquals("UNKNOWN", result.getValue().result());
        assertEquals("REDIS_UNAVAILABLE", result.getValue().errorCode());
        assertFalse(new ObjectMapper().writeValueAsString(result.getValue()).contains("raw-redis-secret"));
    }
}
