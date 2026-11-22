package me.lendev.flow.mail.transport;

import me.lendev.flow.mail.MailModels.Connection;
import me.lendev.flow.mail.MailModels.GraphSettings;
import me.lendev.flow.mail.MailModels.InboxMessage;
import me.lendev.flow.mail.MailModels.InboxPage;
import me.lendev.flow.mail.MailModels.InboxQuery;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RenderedMail;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailModels.Submission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 使用应用身份访问 Exchange Online 收件箱并提交邮件。
 */
@Component
final class GraphMailTransport implements MailTransport {

    private static final String GRAPH_HOST = "graph.microsoft.com";
    private static final String GRAPH_ROOT = "https://" + GRAPH_HOST + "/v1.0/users/";
    private static final String TOKEN_ROOT = "https://login.microsoftonline.com/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int GRAPH_PAGE_SIZE = 50;
    private static final int MAX_INBOX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TOKEN_RESPONSE_BYTES = 64 * 1024;
    private static final int HTTP_OK = 200;
    private static final int HTTP_ACCEPTED = 202;
    private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    GraphMailTransport(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    GraphMailTransport(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public Provider provider() {
        return Provider.GRAPH;
    }

    @Override
    public InboxPage read(Connection connection, InboxQuery query, String providerCursor) {
        Cursor cursor = Cursor.decode(providerCursor);
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        String accessToken = accessToken(connection, deadline);
        URI endpoint = mailboxUri(connection, "/mailFolders/inbox/messages");
        var matches = new TreeSet<>(InboxFilters.NEWEST_FIRST);
        var visitedPages = new HashSet<URI>();
        URI next = inboxUri(endpoint, query, cursor);
        try {
            while (next != null && visitedPages.add(next)) {
                JsonNode page = readPage(next, accessToken, deadline);
                if (collectMatches(page, query, cursor, matches)) {
                    return toPage(query, matches);
                }
                next = nextLink(endpoint, page);
            }
            if (next != null) {
                throw invalidResponse();
            }
            return toPage(query, matches);
        } catch (IOException | DateTimeParseException | JacksonException | IllegalArgumentException exception) {
            throw new MailTransportException("MAIL_GRAPH_READ_FAILED", "无法读取 Microsoft Graph 收件箱");
        }
    }

    private JsonNode readPage(URI uri, String accessToken, long deadline) throws IOException {
        var request = graphRequest(uri, accessToken)
                .header("Prefer", "IdType=\"ImmutableId\"")
                .GET()
                .build();
        var response = exchange(request, deadline, ignored -> new LimitedBodySubscriber(MAX_INBOX_RESPONSE_BYTES));
        if (response.statusCode() != HTTP_OK) {
            throw new MailTransportException("MAIL_GRAPH_HTTP_" + response.statusCode(), "Microsoft Graph 拒绝读取收件箱");
        }
        return mapper.readTree(response.body());
    }

    /**
     * 读取足够的邮件并处理接收时间相同的整组邮件。Graph 按接收时间排序，
     * 同时间的结果可能跨页，因此在确认读完该时间范围后再返回。
     */
    private boolean collectMatches(JsonNode page, InboxQuery query, Cursor cursor, TreeSet<InboxMessage> matches) {
        JsonNode values = page.path("value");
        if (!values.isArray()) {
            throw invalidResponse();
        }
        for (JsonNode value : values) {
            InboxMessage message = toMessage(value, query.includeBody());
            if (matches.size() > query.pageSize() && message.receivedAt().isBefore(matches.last().receivedAt())) {
                return true;
            }
            if (InboxFilters.matches(query, message) && (cursor == null || cursor.precedes(message))) {
                matches.add(message);
                if (matches.size() > query.pageSize() + 1) {
                    matches.pollLast();
                }
            }
        }
        return false;
    }

    private static InboxMessage toMessage(JsonNode item, boolean includeBody) {
        String id = item.path("id").asString(null);
        if (id == null || id.isBlank() || !item.path("isRead").isBoolean()) {
            throw invalidResponse();
        }
        Instant receivedAt = Instant.parse(item.path("receivedDateTime").asString(""));
        JsonNode from = item.path("from").path("emailAddress");
        JsonNode body = item.path("body");
        String content = includeBody ? body.path("content").asString(null) : null;
        String format = content == null ? null : body.path("contentType").asString("html").toUpperCase(Locale.ROOT);
        return new InboxMessage(id, from.path("address").asString(null), from.path("name").asString(null),
                item.path("subject").asString(null), receivedAt, item.path("isRead").asBoolean(), content, format);
    }

    private static URI inboxUri(URI endpoint, InboxQuery query, Cursor cursor) {
        List<String> filters = new ArrayList<>();
        if (query.receivedFrom() != null) {
            filters.add("receivedDateTime ge " + query.receivedFrom());
        }
        if (query.receivedTo() != null) {
            filters.add("receivedDateTime lt " + query.receivedTo());
        }
        if (cursor != null) {
            filters.add("receivedDateTime le " + cursor.receivedAt());
        }
        // Graph 要求组合筛选与排序时，排序字段出现在其他筛选字段之前。
        if (query.read() != null && !filters.isEmpty()) {
            filters.add("isRead eq " + query.read());
        }
        String select = "id,from,subject,receivedDateTime,isRead" + (query.includeBody() ? ",body" : "");
        String filter = filters.isEmpty() ? "" : "&$filter=" + encode(String.join(" and ", filters));
        return URI.create(endpoint + "?$top=" + GRAPH_PAGE_SIZE + "&$select=" + encode(select)
                + "&$orderby=" + encode("receivedDateTime desc") + filter);
    }

    private static InboxPage toPage(InboxQuery query, TreeSet<InboxMessage> matches) {
        if (matches.size() <= query.pageSize()) {
            return new InboxPage(List.copyOf(matches), null);
        }
        matches.pollLast();
        InboxMessage last = matches.last();
        return new InboxPage(List.copyOf(matches), new Cursor(last.receivedAt(), last.id()).encode());
    }

    private static URI nextLink(URI endpoint, JsonNode page) {
        String link = page.path("@odata.nextLink").asString(null);
        if (link == null) {
            return null;
        }
        URI next = URI.create(link);
        if (!"https".equalsIgnoreCase(next.getScheme()) || !GRAPH_HOST.equalsIgnoreCase(next.getHost())
                || next.getPort() != -1 || next.getRawUserInfo() != null || next.getRawFragment() != null
                || !endpoint.getPath().equals(next.getPath()) || next.getRawQuery() == null) {
            throw invalidResponse();
        }
        return next;
    }

    private static MailTransportException invalidResponse() {
        return new MailTransportException("MAIL_GRAPH_RESPONSE_INVALID", "Microsoft Graph 返回无效的邮件数据");
    }

    @Override
    public Submission send(Connection connection, List<Recipient> recipients, RenderedMail mail) {
        HttpRequest request;
        try {
            String accessToken = accessToken(connection, System.nanoTime() + REQUEST_TIMEOUT.toNanos());
            request = graphRequest(mailboxUri(connection, "/sendMail"), accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(sendBody(recipients, mail), UTF_8))
                    .build();
        } catch (MailTransportException exception) {
            return new Submission(Status.FAILED, null, exception.code(), exception.getMessage(), List.of());
        } catch (IllegalArgumentException | JacksonException exception) {
            return new Submission(Status.FAILED, null, "MAIL_GRAPH_PAYLOAD_INVALID", "无法生成 Microsoft Graph 邮件请求", List.of());
        }

        try {
            var response = exchange(request, System.nanoTime() + REQUEST_TIMEOUT.toNanos(),
                    HttpResponse.BodyHandlers.replacing(""));
            int status = response.statusCode();
            String requestId = response.headers().firstValue("request-id")
                    .filter(value -> REQUEST_ID.matcher(value).matches()).orElse(null);
            if (status == HTTP_ACCEPTED) {
                return new Submission(Status.ACCEPTED, requestId, null, null, List.of());
            }
            boolean rejected = status >= 400 && status < 500 && status != 408;
            return new Submission(rejected ? Status.FAILED : Status.UNKNOWN, requestId, "MAIL_GRAPH_HTTP_" + status,
                    rejected ? "Microsoft Graph 拒绝提交邮件" : "无法确认 Microsoft Graph 是否受理邮件", List.of());
        } catch (IOException exception) {
            return new Submission(Status.UNKNOWN, null, "MAIL_GRAPH_SUBMISSION_UNKNOWN",
                    "无法确认 Microsoft Graph 是否受理邮件", List.of());
        }
    }

    private String sendBody(List<Recipient> recipients, RenderedMail mail) {
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode message = payload.putObject("message");
        message.put("subject", mail.subject());
        message.putObject("body").put("contentType", "HTML").put("content", mail.html());
        var to = message.putArray("toRecipients");
        for (Recipient recipient : recipients) {
            var address = to.addObject().putObject("emailAddress").put("address", recipient.address());
            if (recipient.name() != null) {
                address.put("name", recipient.name());
            }
        }
        return mapper.writeValueAsString(payload);
    }

    private String accessToken(Connection connection, long deadline) {
        try {
            GraphSettings graph = connection.settings().graph();
            URI endpoint = URI.create(TOKEN_ROOT + encode(graph.tenantId()) + "/oauth2/v2.0/token");
            String body = "grant_type=client_credentials&scope=" + form("https://" + GRAPH_HOST + "/.default")
                    + "&client_id=" + form(graph.clientId()) + "&client_secret=" + form(connection.credentials().clientSecret());
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                    .build();
            var response = exchange(request, deadline, ignored -> new LimitedBodySubscriber(MAX_TOKEN_RESPONSE_BYTES));
            if (response.statusCode() == HTTP_OK) {
                String accessToken = mapper.readTree(response.body()).path("access_token").asString("");
                if (!accessToken.isBlank()) {
                    return accessToken;
                }
            }
        } catch (IOException | IllegalArgumentException | JacksonException exception) {
            throw authFailed();
        }
        throw authFailed();
    }

    private static MailTransportException authFailed() {
        return new MailTransportException("MAIL_GRAPH_AUTH_FAILED", "Microsoft Graph 应用认证失败");
    }

    private static URI mailboxUri(Connection connection, String operation) {
        String mailboxId = connection.settings().graph().mailboxId();
        String user = mailboxId == null || mailboxId.isBlank() ? connection.mailboxAddress() : mailboxId;
        return URI.create(GRAPH_ROOT + encode(user) + operation);
    }

    private static HttpRequest.Builder graphRequest(URI uri, String accessToken) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken);
    }

    private HttpResponse<String> exchange(HttpRequest request, long deadline, HttpResponse.BodyHandler<String> bodyHandler)
            throws IOException {
        long remaining = Math.min(REQUEST_TIMEOUT.toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) {
            throw new IOException("Graph request deadline elapsed");
        }
        var pending = client.sendAsync(request, bodyHandler);
        try {
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Graph request interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            pending.cancel(true);
            throw new IOException("Graph request failed");
        }
    }

    /**
     * 在响应累积为字符串前按实际接收的字节限额，超限时取消 HTTP 响应订阅。
     */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(UTF_8);
        private Flow.Subscription subscription;
        private long remaining;
        private boolean done;

        private LimitedBodySubscriber(int maxBytes) {
            remaining = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription upstream) {
            subscription = upstream;
            delegate.onSubscribe(upstream);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            remaining -= buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (remaining < 0) {
                subscription.cancel();
                onError(new IOException("Graph response exceeds the byte limit"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!done) {
                done = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }

    private static String encode(String value) {
        return UriUtils.encode(value, UTF_8);
    }

    private static String form(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    private record Cursor(Instant receivedAt, String id) {

        boolean precedes(InboxMessage message) {
            int timeComparison = receivedAt.compareTo(message.receivedAt());
            return timeComparison > 0 || timeComparison == 0 && id.compareTo(message.id()) > 0;
        }

        String encode() {
            return receivedAt + "\n" + id;
        }

        static Cursor decode(String raw) {
            if (raw == null) {
                return null;
            }
            String[] parts = raw.split("\n", -1);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw MailTransportException.invalidCursor();
            }
            try {
                return new Cursor(Instant.parse(parts[0]), parts[1]);
            } catch (DateTimeParseException exception) {
                throw MailTransportException.invalidCursor();
            }
        }
    }
}
