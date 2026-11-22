package me.lendev.flow.mail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 邮件业务接口及两种协议共用的数据契约。
 */
public final class MailModels {
    public static final int MAX_SUBJECT = 500;
    public static final int MAX_BODY = 20_000;
    public static final int MAX_RECIPIENTS = 25;
    public static final int MAX_VARIABLES = 100;

    public enum Provider {IMAP_SMTP, GRAPH}

    public enum Tls {SSL, STARTTLS}

    public enum SendMode {INDIVIDUAL, TOGETHER}

    public enum Status {PENDING, PROCESSING, ACCEPTED, PARTIAL, FAILED, UNKNOWN}

    public record Endpoint(String host, int port, String username, Tls tls) {
    }

    public record GraphSettings(String tenantId, String clientId, String mailboxId) {
        public GraphSettings(String tenantId, String clientId) {
            this(tenantId, clientId, null);
        }
    }

    public record Settings(Endpoint imap, Endpoint smtp, GraphSettings graph) {
    }

    public record Credentials(String imapPassword, String smtpPassword, String clientSecret) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public record Connection(Provider provider, String mailboxAddress, Settings settings, Credentials credentials) {
    }

    public record Recipient(@NotBlank @Email @Size(max = 254) String address, @Size(max = 200) String name) {
    }

    public record PreviewRecipient(@Email @Size(max = 254) String address, @Size(max = 200) String name) {
    }

    public record RenderedMail(String subject, String html) {
    }

    public record InboxQuery(String sender, String subject, Instant receivedFrom, Instant receivedTo,
                             Boolean read, boolean includeBody, int pageSize) {
    }

    public record InboxMessage(String id, String sender, String senderName, String subject, Instant receivedAt,
                               boolean read, String body, String bodyFormat) {
    }

    public record InboxPage(List<InboxMessage> messages, String nextCursor) {
    }

    public record RecipientResult(String address, Status status) {
    }

    public record Submission(Status status, String providerRequestId, String errorCode, String errorMessage,
                             List<RecipientResult> recipientResults) {
    }

    public record SendRequest(@NotBlank @Size(max = 64) String templateCode,
                              @NotNull @Size(min = 1, max = MAX_RECIPIENTS) List<@NotNull @Valid Recipient> recipients,
                              @Size(max = MAX_VARIABLES) Map<@Size(max = 100) String, @Size(max = 10_000) String> variables,
                              @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String idempotencyKey) {
    }

    public record PreviewRequest(@NotBlank @Size(max = MAX_SUBJECT) String subjectTemplate,
                                 @NotBlank @Size(max = MAX_BODY) String bodyTemplate, @NotNull SendMode sendMode,
                                 @Size(max = MAX_VARIABLES) Map<@Size(max = 100) String, @Size(max = 10_000) String> variables,
                                 @Valid PreviewRecipient recipient) {
    }

    public record SendResult(long logId, Status status) {
    }

    private MailModels() {
    }
}
