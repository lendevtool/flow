package me.lendev.flow.mail;

import static me.lendev.flow.mail.MailModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.time.Instant;
import java.util.List;

import me.lendev.flow.mail.transport.MailTransport;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@TestPropertySource(properties = "flow.mail.credential-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MailBusinessApiTest extends ApiTestBase {
    @MockitoBean(name = "graphMailTransport")
    MailTransport graph;

    @Test
    void appliesSharedContractsFromInboxThroughIdempotentSubmissionAndLogDetails() throws Exception {
        when(graph.provider()).thenReturn(Provider.GRAPH);
        var query = new InboxQuery(null, "进展", Instant.parse("2026-11-15T00:00:00Z"), null, false, false, 20);
        when(graph.read(any(), eq(query), isNull())).thenReturn(new InboxPage(List.of(
                new InboxMessage("message-1", "from@example.com", null, "项目进展",
                        Instant.parse("2026-11-15T12:00:00Z"), false, null, null)), "next-one"));
        when(graph.read(any(), eq(query), eq("next-one"))).thenReturn(new InboxPage(List.of(), null));
        String token = loginAsAdmin();
        var account = data(postJson("/mail/accounts", token, map("code", "BUSINESS_TEST_MAIL", "name", "业务测试邮箱",
                "provider", "GRAPH", "enable", true, "mailboxAddress", "mail@example.com",
                "config", map("graph", map("tenantId", "89ef1f2e-2e01-4664-8d56-65c87d2d4703",
                        "clientId", "73952fdc-376f-44f5-a07b-aaeeaa9e9591")),
                "credentials", map("clientSecret", "fixture-secret"))));

        var inbox = data(getJson("/mail/inbox", token, "accountCode", "BUSINESS_TEST_MAIL", "subject", "进展",
                "receivedFrom", "2026-11-15T08:00:00+08:00", "read", false));
        String cursor = inbox.path("nextCursor").asString();
        assertEquals("项目进展", inbox.path("messages").get(0).path("subject").asString());
        var next = data(getJson("/mail/inbox", token, "accountCode", "BUSINESS_TEST_MAIL", "subject", "进展",
                "receivedFrom", "2026-11-15T00:00:00Z", "read", false, "cursor", cursor));
        assertEquals(0, next.path("messages").size());

        data(postJson("/mail/templates", token, map("code", "BUSINESS_TEST_NOTICE", "name", "业务模板",
                "accountId", account.path("id").asLong(), "subjectTemplate", "通知", "bodyTemplate", "**{{recipient.name}}**",
                "bodyFormat", "MARKDOWN", "sendMode", "INDIVIDUAL", "enable", true)));
        var preview = data(postJson("/mail/templates/preview", token, map("subjectTemplate", "通知",
                "bodyTemplate", "**{{recipient.name}}**", "sendMode", "INDIVIDUAL", "recipient", map("name", "甲"))));
        assertEquals("<p><strong>甲</strong></p>\n", preview.path("html").asString());
        when(graph.send(any(), eq(List.of(new Recipient("first@example.com", "甲"))),
                eq(new RenderedMail("通知", "<p><strong>甲</strong></p>\n"))))
                .thenReturn(new Submission(Status.ACCEPTED, "p1", null, null, null));
        when(graph.send(any(), eq(List.of(new Recipient("second@example.com", "乙"))), any()))
                .thenReturn(new Submission(Status.FAILED, "p2", "MAIL_GRAPH_REJECTED", "邮件服务拒绝提交", null));
        var request = map("templateCode", "business_test_notice", "recipients", List.of(
                        map("address", "first@example.com", "name", "甲"), map("address", "second@example.com", "name", "乙")),
                "idempotencyKey", "business-test-once");
        var sent = data(postJson("/mail/send", token, request).andExpect(header().exists("X-Request-ID")));
        assertEquals("PARTIAL", sent.path("status").asString());
        assertEquals(sent, data(postJson("/mail/send", token, request)));
        request.put("variables", map("unused", "different request"));
        body(postJson("/mail/send", token, request)
                .andExpect(header().string("X-Mail-Error-Code", "MAIL_IDEMPOTENCY_CONFLICT")), 409);
        verify(graph, times(2)).send(any(), anyList(), any());

        var log = data(getJson("/mail/logs/" + sent.path("logId").asLong(), token));
        assertEquals("PARTIAL", log.path("status").asString());
        assertEquals("ACCEPTED", log.path("details").get(0).path("status").asString());
        assertEquals("FAILED", log.path("details").get(1).path("status").asString());
        assertFalse(log.toString().contains("<strong>"));
    }
}
