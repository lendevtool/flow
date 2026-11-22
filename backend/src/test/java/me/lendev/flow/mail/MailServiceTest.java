package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.mail.MailAdminService.AccountConnection;
import me.lendev.flow.mail.MailAdminService.BoundTemplate;
import me.lendev.flow.mail.MailStore.SendDetail;
import me.lendev.flow.mail.MailStore.StoredSend;
import me.lendev.flow.mail.transport.MailTransport;
import me.lendev.flow.mail.transport.MailTransportException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class MailServiceTest {
    private static final Recipient FIRST = new Recipient("first@example.com", "一号");
    private static final Recipient SECOND = new Recipient("second@example.com", "二号");
    private static final Connection CONNECTION = new Connection(Provider.GRAPH, "from@example.com", null, null);
    private final MailAdminService admin = mock(MailAdminService.class);
    private final MailStore store = mock(MailStore.class);
    private final MailTransport transport = mock(MailTransport.class);
    private final MailSecurity security = new MailSecurity(JsonMapper.builder().build(), Clock.systemUTC(),
            Base64.getEncoder().encodeToString(new byte[32]));
    private final MailService service = new MailService(admin, store, new MailTemplateRenderer(), security,
            List.of(transport), Clock.systemUTC());

    @Test
    void validatesEveryRecipientBeforeReservingOrSubmitting() {
        when(admin.loadTemplate("TODO")).thenReturn(template(SendMode.INDIVIDUAL, "{{recipient.name}}"));

        var request = new SendRequest("todo", List.of(FIRST, new Recipient(SECOND.address(), null)), Map.of(), null);
        var error = assertThrows(BadRequestException.class, () -> service.send(request, "u1", "r1"));

        assertTrue(error.getMessage().contains("recipient.name"));
        assertTrue(error.getMessage().contains("2"));
        verifyNoInteractions(store, transport);
    }

    @Test
    void returnsAnExistingSendWithoutResolvingTheTemplateOrResending() {
        var stored = mock(StoredSend.class, RETURNS_DEEP_STUBS);
        when(stored.log().id()).thenReturn(19L);
        when(stored.log().status()).thenReturn(Status.ACCEPTED);
        when(stored.log().requestDigest()).thenReturn(security.digest(List.of("TODO", List.of(FIRST), Map.of("name", "原值"))));
        when(store.findSend("u1", "send-1")).thenReturn(Optional.of(stored));
        var request = new SendRequest("todo", List.of(FIRST), Map.of("name", "原值"), "send-1");

        assertEquals(new SendResult(19L, Status.ACCEPTED), service.send(request, "u1", "r2"));
        var changed = new SendRequest("todo", List.of(FIRST), Map.of("name", "不同值"), "send-1");
        assertThrows(MailOperationException.class, () -> service.send(changed, "u1", "r3"));
        verifyNoInteractions(admin, transport);
    }

    @Test
    void internalReplaysByTemplateIdEvenIfTheTemplateCodeHasChanged() {
        when(admin.loadTemplate(42L)).thenReturn(new BoundTemplate(42L, "RENAMED", 7L, "SENDER", 3,
                "Subject", "正文 {{targetNode.name}}", SendMode.INDIVIDUAL, CONNECTION));
        when(transport.provider()).thenReturn(Provider.GRAPH);
        var stored = mock(StoredSend.class, RETURNS_DEEP_STUBS);
        when(stored.log().id()).thenReturn(19L);
        when(stored.log().status()).thenReturn(Status.ACCEPTED);
        when(store.findSend("SYSTEM:WORKFLOW_NOTIFICATION", "wf-notify:1:9"))
                .thenReturn(Optional.empty(), Optional.of(stored));
        when(store.reserve(any(), eq(List.of(List.of(FIRST))))).thenAnswer(call -> {
            MailStore.SendReservation request = call.getArgument(0);
            assertEquals(42L, request.templateId());
            assertEquals("RENAMED", request.templateCode());
            assertEquals(3, request.templateRevision());
            when(stored.log().requestDigest()).thenReturn(request.requestDigest());
            return stored;
        });
        var variables = Map.of("targetNode.name", "");

        assertEquals(new SendResult(19L, Status.ACCEPTED), service.sendByTemplateId(42L, FIRST, variables,
                "SYSTEM:WORKFLOW_NOTIFICATION", "wf-notify:1:9", "r1"));
        assertEquals(new SendResult(19L, Status.ACCEPTED), service.sendByTemplateId(42L, FIRST, variables,
                "SYSTEM:WORKFLOW_NOTIFICATION", "wf-notify:1:9", "r2"));
        verify(admin).loadTemplate(42L);
        verify(store).reserve(any(), anyList());
    }

    @Test
    void reservesOneSubmissionPerRecipientOrOneForTheGroup() {
        when(transport.provider()).thenReturn(Provider.GRAPH);
        when(store.reserve(any(), anyList())).thenReturn(mock(StoredSend.class, RETURNS_DEEP_STUBS));
        var request = new SendRequest("todo", List.of(FIRST, SECOND), Map.of(), null);
        when(admin.loadTemplate("TODO")).thenReturn(template(SendMode.INDIVIDUAL, "正文"));
        service.send(request, "u1", "r1");
        verify(store).reserve(any(), eq(List.of(List.of(FIRST), List.of(SECOND))));

        when(admin.loadTemplate("TODO")).thenReturn(template(SendMode.TOGETHER, "正文"));
        service.send(request, "u1", "r2");
        verify(store).reserve(any(), eq(List.of(List.of(FIRST, SECOND))));
    }

    @Test
    void preservesUncertainSubmissionWithoutRetryAndRecoversAbandonedWork() {
        when(transport.provider()).thenReturn(Provider.GRAPH);
        when(admin.loadTemplate("TODO")).thenReturn(template(SendMode.INDIVIDUAL, "正文"));
        var persisted = mock(StoredSend.class, RETURNS_DEEP_STUBS);
        when(persisted.created()).thenReturn(true);
        when(persisted.log().id()).thenReturn(11L);
        when(persisted.log().status()).thenReturn(Status.UNKNOWN);
        when(persisted.details()).thenReturn(List.of(new SendDetail(10L, 1, List.of(FIRST), List.of(),
                Status.PENDING, null, null, null, null, null)));
        when(store.reserve(any(), anyList())).thenReturn(persisted);
        when(store.claim(10L)).thenReturn(true);
        when(store.send(11L)).thenReturn(persisted);
        when(transport.send(eq(CONNECTION), anyList(), any())).thenThrow(new IllegalStateException("payload must not leak"));

        var result = service.send(new SendRequest("TODO", List.of(FIRST), null, null), "u1", "r1");

        assertEquals(new SendResult(11L, Status.UNKNOWN), result);
        verify(transport).send(eq(CONNECTION), anyList(), any());
        verify(store).finish(eq(10L), argThat(submission -> submission.status() == Status.UNKNOWN
                && submission.errorCode().equals("MAIL_PROVIDER_UNCERTAIN")
                && !submission.errorMessage().contains("payload")));

        when(store.expire(any())).thenReturn(2);
        service.recoverAbandonedSends();
        verify(store).expire(any());
    }

    @Test
    void rejectsDuplicateRecipientsBeforeOpeningAnAccountAndChecksInboxDateOrder() {
        var request = new SendRequest("TODO", List.of(FIRST, new Recipient("FIRST@example.com", null)), null, null);
        assertThrows(BadRequestException.class, () -> service.send(request, "u1", "r1"));
        var time = Instant.parse("2026-11-16T01:00:00Z");
        var reversed = new InboxQuery(null, null, time, time.minusSeconds(1), null, false, 20);
        assertThrows(BadRequestException.class, () -> service.inbox("ACCOUNT", reversed, null, "u1", "r2"));
        verifyNoInteractions(admin, store, transport);
    }

    @Test
    void mapsProviderErrorsToSafeAndStableHttpSemantics() {
        when(transport.provider()).thenReturn(Provider.GRAPH);
        when(admin.loadAccountConnection("ACCOUNT")).thenReturn(new AccountConnection(1, "ACCOUNT", CONNECTION));
        var query = new InboxQuery(null, null, null, null, null, false, 20);
        var providerFailure = mock(MailTransportException.class);
        when(providerFailure.getMessage()).thenReturn("邮箱查询失败");
        when(providerFailure.code()).thenReturn("MAIL_GRAPH_READ_FAILED");
        when(transport.read(CONNECTION, query, null)).thenThrow(providerFailure);
        var result = assertThrows(MailOperationException.class,
                () -> service.inbox("ACCOUNT", query, null, "u1", "r1"));
        assertEquals(HttpStatus.BAD_GATEWAY, result.status());
        assertEquals("邮箱查询失败", result.getMessage());

        when(providerFailure.code()).thenReturn("MAIL_INBOX_CURSOR_INVALID");
        var invalid = assertThrows(MailOperationException.class,
                () -> service.inbox("ACCOUNT", query, null, "u1", "r2"));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.status());

        when(transport.provider()).thenReturn(Provider.IMAP_SMTP);
        var unavailable = assertThrows(MailOperationException.class,
                () -> service.inbox("ACCOUNT", query, null, "u1", "r3"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.status());
    }

    private static BoundTemplate template(SendMode mode, String body) {
        return new BoundTemplate(1L, "todo", 2L, "account", 1, "主题", body, mode, CONNECTION);
    }
}
