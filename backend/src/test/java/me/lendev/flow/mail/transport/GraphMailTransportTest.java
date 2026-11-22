package me.lendev.flow.mail.transport;

import me.lendev.flow.mail.MailModels.Connection;
import me.lendev.flow.mail.MailModels.Credentials;
import me.lendev.flow.mail.MailModels.GraphSettings;
import me.lendev.flow.mail.MailModels.InboxQuery;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RenderedMail;
import me.lendev.flow.mail.MailModels.Settings;
import me.lendev.flow.mail.MailModels.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphMailTransportTest {

    private static final String INBOX_URL = "https://graph.microsoft.com/v1.0/users/service%40example.com/mailFolders/inbox/messages";
    private static final String SEND_URL = "https://graph.microsoft.com/v1.0/users/service%40example.com/sendMail";
    private static final Connection CONNECTION = new Connection(
            Provider.GRAPH, "service@example.com", new Settings(null, null, new GraphSettings("tenant", "app")),
            new Credentials(null, null, "test+secret &=")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final Queue<CompletableFuture<HttpResponse<String>>> responses = new ArrayDeque<>();
    private HttpClient client;
    private GraphMailTransport transport;

    @BeforeEach
    void configureHttpBoundary() {
        client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return responses.remove();
        });
        transport = new GraphMailTransport(mapper, client);
    }

    @Test
    void submitsHtmlAndOnlyReportsAcceptanceAtTheMessageLevel() {
        token();
        respond(202, "", Map.of("request-id", List.of("edbff76c-2452-4bf8-bd74-52bab0acb2bb")));

        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", "Reader")),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(transport.provider()).isEqualTo(Provider.GRAPH);
        assertThat(result.status()).isEqualTo(Status.ACCEPTED);
        assertThat(result.providerRequestId()).isEqualTo("edbff76c-2452-4bf8-bd74-52bab0acb2bb");
        assertThat(result.recipientResults()).isEmpty();
        HttpRequest auth = requests.getFirst();
        assertThat(auth.uri()).isEqualTo(URI.create("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"));
        assertThat(requestBody(auth))
                .contains("grant_type=client_credentials", "scope=https%3A%2F%2Fgraph.microsoft.com%2F.default",
                        "client_secret=test%2Bsecret+%26%3D");
        HttpRequest send = requests.getLast();
        assertThat(send.method()).isEqualTo("POST");
        assertThat(send.uri()).isEqualTo(URI.create(SEND_URL));
        assertThat(send.headers().firstValue("Authorization")).contains("Bearer token");
        assertThat(mapper.readTree(requestBody(send))).isEqualTo(mapper.readTree("""
                {
                  "message":{
                    "subject":"Subject",
                    "body":{"contentType":"HTML","content":"<p>Content</p>"},
                    "toRecipients":[{"emailAddress":{"address":"reader@example.net","name":"Reader"}}]
                  }
                }
                """));
    }

    @ParameterizedTest
    @CsvSource({"403,FAILED", "429,FAILED", "408,UNKNOWN", "500,UNKNOWN", "502,UNKNOWN", "200,UNKNOWN"})
    void distinguishesExplicitRejectionFromUncertainAcceptance(int statusCode, Status expected) {
        token();
        respond(statusCode, "Test subject and private recipient", Map.of("request-id", List.of("unsafe@example.net")));

        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Test subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.providerRequestId()).isNull();
        assertThat(result.errorCode()).isEqualTo("MAIL_GRAPH_HTTP_" + statusCode);
        assertThat(result.errorMessage()).doesNotContain("Test subject", "private recipient");
        assertThat(result.recipientResults()).isEmpty();
    }

    @Test
    void treatsIoFailureAfterStartingSubmissionAsUncertain() {
        token();
        responses.add(CompletableFuture.failedFuture(new IOException("private recipient")));
        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.errorMessage()).doesNotContain("private recipient");
        assertThat(requests).hasSize(2);
    }

    @Test
    void tokenFailureCannotHaveSubmittedAMessage() {
        respond(401, "test+secret &=", Map.of());
        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorMessage()).doesNotContain("test+secret &=");
        assertThat(requests).hasSize(1);
    }

    @Test
    void rejectsAnInvalidTokenResponseBeforeAttemptingToSend() {
        respond(200, "{malformed authentication payload", Map.of());

        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("MAIL_GRAPH_AUTH_FAILED");
        assertThat(result.errorMessage()).doesNotContain("malformed authentication payload");
        assertThat(requests).hasSize(1);
    }

    @Test
    void rejectsAnOversizeTokenResponseBeforeAttemptingToSend() {
        var subscription = streamingResponse("login.microsoftonline.com", 200,
                "{\"access_token\":\"test-token\"}", 64 * 1024);
        respond(202, "", Map.of());

        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("MAIL_GRAPH_AUTH_FAILED");
        verify(subscription).cancel();
        verify(client).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void cancelsAnOversizeInboxPageWithoutContentLength() {
        token();
        var subscription = streamingResponse("graph.microsoft.com", 200, "{\"value\":[]}", 16 * 1024 * 1024);
        InboxQuery query = query(true);

        assertThatThrownBy(() -> transport.read(CONNECTION, query, null))
                .isInstanceOfSatisfying(MailTransportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MAIL_GRAPH_READ_FAILED"));
        verify(subscription).cancel();
    }

    @Test
    void discardsAnUnusedSendResponseBodyAndKeepsTheAcceptance() {
        token();
        streamingResponse("graph.microsoft.com", 202, "unused provider response", 128 * 1024);

        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.ACCEPTED);
    }

    @Test
    void doesNotSubmitWhenTheAuthorizationHeaderCannotBeBuilt() {
        respond(200, "{\"access_token\":\"token\\r\\nunexpected-header\",\"token_type\":\"Bearer\"}", Map.of());

        var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("MAIL_GRAPH_PAYLOAD_INVALID");
        assertThat(result.errorMessage()).doesNotContain("unexpected-header");
        assertThat(requests).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelsAnInterruptedSubmissionAndPreservesTheThreadInterrupt() throws Exception {
        token();
        CompletableFuture<HttpResponse<String>> pending = mock(CompletableFuture.class);
        when(pending.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException("private request"));
        responses.add(pending);

        try {
            var result = transport.send(CONNECTION, List.of(new Recipient("reader@example.net", null)),
                    new RenderedMail("Subject", "<p>Content</p>"));

            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.errorMessage()).doesNotContain("private request");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(pending).cancel(true);
    }

    @Test
    void usesTheConfiguredEntraMailboxIdWhenItDiffersFromTheMailboxAddress() {
        token();
        respond(202, "", Map.of());
        var connection = new Connection(Provider.GRAPH, "service@example.com",
                new Settings(null, null, new GraphSettings("tenant", "app", "user@tenant.onmicrosoft.com")),
                new Credentials(null, null, "secret"));

        var result = transport.send(connection, List.of(new Recipient("reader@example.net", null)),
                new RenderedMail("Subject", "<p>Content</p>"));

        assertThat(result.status()).isEqualTo(Status.ACCEPTED);
        assertThat(requests.getLast().uri()).isEqualTo(
                URI.create("https://graph.microsoft.com/v1.0/users/user%40tenant.onmicrosoft.com/sendMail"));
    }

    @Test
    void filtersExactlyAndSortsTimestampTiesAcrossProviderPages() {
        String continuation = INBOX_URL + "?%24skip=123";
        token();
        respond(200, """
                {"value":[
                  {"id":"excluded-end","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:30Z","isRead":false},
                  {"id":"a","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New STATUS","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false}
                ],"@odata.nextLink":"%s"}
                """.formatted(continuation), Map.of());
        respond(200, """
                {"value":[
                  {"id":"z","from":{"emailAddress":{"address":"SENDER@example.com","name":"Sender"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false,"body":{"contentType":"html","content":"<p>body</p>"}},
                  {"id":"wrong-sender","from":{"emailAddress":{"address":"not-sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:19Z","isRead":false},
                  {"id":"wrong-read","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:18Z","isRead":true},
                  {"id":"wrong-subject","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"Other","receivedDateTime":"2026-01-01T12:00:17Z","isRead":false},
                  {"id":"boundary-start","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:10Z","isRead":false}
                ]}
                """, Map.of());
        InboxQuery query = query(true);

        var first = transport.read(CONNECTION, query, null);

        assertThat(requests.get(1).uri().getQuery()).contains(
                "receivedDateTime ge 2026-01-01T12:00:10Z", "receivedDateTime lt 2026-01-01T12:00:30Z",
                "$orderby=receivedDateTime desc", "$select=id,from,subject,receivedDateTime,isRead,body");
        assertThat(requests.get(2).uri()).isEqualTo(URI.create(continuation));
        assertThat(requests.stream().skip(1)).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().firstValue("Prefer")).contains("IdType=\"ImmutableId\"");
        });
        assertThat(first.messages()).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo("z");
            assertThat(message.senderName()).isEqualTo("Sender");
            assertThat(message.body()).isEqualTo("<p>body</p>");
            assertThat(message.bodyFormat()).isEqualTo("HTML");
            assertThat(message.read()).isFalse();
        });
        assertThat(first.nextCursor()).isNotBlank();

        token();
        respond(200, """
                {"value":[
                  {"id":"z","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false},
                  {"id":"a","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false},
                  {"id":"boundary-start","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:10Z","isRead":false}
                ]}
                """, Map.of());
        var second = transport.read(CONNECTION, query, first.nextCursor());
        assertThat(requests.getLast().uri().getQuery()).contains("receivedDateTime le 2026-01-01T12:00:20Z");
        assertThat(second.messages()).singleElement().satisfies(message -> assertThat(message.id()).isEqualTo("a"));
        assertThat(second.nextCursor()).isNotBlank();

        token();
        respond(200, """
                {"value":[
                  {"id":"boundary-start","from":{"emailAddress":{"address":"sender@example.com"}},
                   "subject":"New status","receivedDateTime":"2026-01-01T12:00:10Z","isRead":false}
                ]}
                """, Map.of());
        var third = transport.read(CONNECTION, query, second.nextCursor());
        assertThat(third.messages()).singleElement().satisfies(message -> assertThat(message.id()).isEqualTo("boundary-start"));
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void keepsTheHighestIdsWhenAnEntirePageSharesATimestamp() {
        token();
        respond(200, """
                {"value":[
                  {"id":"a","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false},
                  {"id":"b","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false},
                  {"id":"z","receivedDateTime":"2026-01-01T12:00:20Z","isRead":false},
                  {"id":"c","receivedDateTime":"2026-01-01T12:00:10Z","isRead":false}
                ]}
                """, Map.of());
        InboxQuery query = new InboxQuery(null, null, null, null, null, false, 1);

        var page = transport.read(CONNECTION, query, null);

        assertThat(page.messages()).singleElement().satisfies(message -> assertThat(message.id()).isEqualTo("z"));
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void stopsWhenGraphReturnsTheSameContinuationAgain() {
        String continuation = INBOX_URL + "?%24skip=123";
        String page = """
                {"value":[],"@odata.nextLink":"%s"}
                """.formatted(continuation);
        token();
        respond(200, page, Map.of());
        respond(200, page, Map.of());
        InboxQuery query = query(false);

        assertThatThrownBy(() -> transport.read(CONNECTION, query, null))
                .isInstanceOfSatisfying(MailTransportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MAIL_GRAPH_RESPONSE_INVALID"));
        assertThat(requests).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"value\":[{}]}", "{\"value\":[{\"id\":\"test\",\"receivedDateTime\":\"error\",\"isRead\":false}]}"})
    void refusesMalformedInboxData(String response) {
        token();
        respond(200, response, Map.of());
        InboxQuery query = query(false);

        assertThatThrownBy(() -> transport.read(CONNECTION, query, null))
                .isInstanceOfSatisfying(MailTransportException.class,
                        exception -> assertThat(exception.code()).isIn("MAIL_GRAPH_RESPONSE_INVALID", "MAIL_GRAPH_READ_FAILED"));
    }

    @Test
    void exposesAnHttpErrorCodeWithoutTheGraphReadErrorBody() {
        token();
        respond(403, "private sender and mailbox path", Map.of());
        InboxQuery query = query(false);

        assertThatThrownBy(() -> transport.read(CONNECTION, query, null))
                .isInstanceOfSatisfying(MailTransportException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MAIL_GRAPH_HTTP_403");
                    assertThat(exception).hasMessageNotContaining("private sender");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "not-a-date\nimmutable-id"})
    void rejectsInvalidCursorsBeforeAuthentication(String cursor) {
        InboxQuery query = query(false);

        assertThatThrownBy(() -> transport.read(CONNECTION, query, cursor))
                .isInstanceOfSatisfying(MailTransportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MAIL_INBOX_CURSOR_INVALID"));
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "https://graph.microsoft.com.evil.example/v1.0/users/service%40example.com/mailFolders/inbox/messages",
            "https://evil.example@graph.microsoft.com/v1.0/users/service%40example.com/mailFolders/inbox/messages",
            "http://graph.microsoft.com/v1.0/users/service%40example.com/mailFolders/inbox/messages",
            "https://graph.microsoft.com/v1.0/users/other%40example.com/mailFolders/inbox/messages",
            "https://graph.microsoft.com:444/v1.0/users/service%40example.com/mailFolders/inbox/messages"
    })
    void refusesContinuationOutsideTheOriginalInboxEndpoint(String continuation) {
        token();
        respond(200, """
                {"value":[],"@odata.nextLink":"%s"}
                """.formatted(continuation), Map.of());
        InboxQuery query = query(false);

        assertThatThrownBy(() -> transport.read(CONNECTION, query, null))
                .isInstanceOf(MailTransportException.class)
                .hasMessageNotContaining(continuation);
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().uri().getQuery()).contains("$select=id,from,subject,receivedDateTime,isRead&")
                .doesNotContain("isRead,body");
    }

    private static InboxQuery query(boolean includeBody) {
        return new InboxQuery("sender@example.com", "status", Instant.parse("2026-01-01T12:00:10Z"),
                Instant.parse("2026-01-01T12:00:30Z"), false, includeBody, 1);
    }

    private void token() {
        respond(200, "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":3599}", Map.of());
    }

    private Flow.Subscription streamingResponse(String host, int status, String content, int paddingBytes) {
        var subscription = mock(Flow.Subscription.class);
        doAnswer(invocation -> {
            HttpResponse.BodyHandler<String> handler = invocation.getArgument(1);
            var info = mock(HttpResponse.ResponseInfo.class);
            var headers = HttpHeaders.of(Map.of(), (name, value) -> true);
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8))));
            byte[] padding = " ".repeat(32 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < paddingBytes; offset += padding.length) {
                int length = Math.min(padding.length, paddingBytes - offset);
                subscriber.onNext(List.of(ByteBuffer.wrap(padding, 0, length)));
            }
            subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().thenApply(body -> {
                @SuppressWarnings("unchecked")
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(status);
                when(response.headers()).thenReturn(headers);
                when(response.body()).thenReturn(body);
                return response;
            });
        }).when(client).sendAsync(argThat(request -> host.equals(request.uri().getHost())),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        return subscription;
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(body);
        responses.add(CompletableFuture.completedFuture(response));
    }

    private static String requestBody(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<Void> copied = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                copied.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                copied.complete(null);
            }
        });
        copied.join();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
