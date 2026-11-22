package me.lendev.flow.mail.transport;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import me.lendev.flow.mail.MailModels.Connection;
import me.lendev.flow.mail.MailModels.Credentials;
import me.lendev.flow.mail.MailModels.Endpoint;
import me.lendev.flow.mail.MailModels.InboxQuery;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RecipientResult;
import me.lendev.flow.mail.MailModels.RenderedMail;
import me.lendev.flow.mail.MailModels.Settings;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailModels.Tls;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.util.SocketFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImapSmtpMailTransportTest {

    private static final String SMTP_HOST = "smtp.example.com";
    private static final String IMAP_HOST = "imap.example.com";
    private static final String SENDER = "sender@example.com";
    private static final String READER = "reader@example.com";
    private static final RenderedMail MAIL = new RenderedMail("邮件主题", "<p>邮件内容</p>");
    private static final List<Recipient> RECIPIENTS = List.of(new Recipient(READER, "读者"));
    private static final InboxQuery INBOX = new InboxQuery(null, null, null, null, null, true, 10);
    private static final String AUTHENTICATED = """
            220 SMTP ready
            250-server
            250 AUTH PLAIN
            235 Authentication successful
            250 sender OK
            """;

    private final ImapSmtpMailTransport transport = new ImapSmtpMailTransport();

    @Test
    void submitsUtf8MimeAndKeepsAcceptanceWhenClosingTheConnectionFails() throws Exception {
        var wire = new ScriptedSmtpSocket(AUTHENTICATED + """
                250 recipient OK
                354 Send message
                250 Message accepted
                """);
        doThrow(new IOException("private cleanup failure")).when(wire.socket).close();
        var expected = ArgumentCaptor.forClass(Properties.class);

        try (var sockets = mockStatic(SocketFetcher.class)) {
            sockets.when(() -> SocketFetcher.getSocket(eq(SMTP_HOST), eq(465), expected.capture(), eq("mail.smtp"), eq(true)))
                    .thenReturn(wire.socket);

            var result = transport.send(connection(Tls.SSL), RECIPIENTS, MAIL);

            assertThat(transport.provider()).isEqualTo(Provider.IMAP_SMTP);
            assertThat(result.status()).isEqualTo(Status.ACCEPTED);
            assertThat(result.errorMessage()).isNull();
            assertThat(result.recipientResults()).isEmpty();
        }
        assertThat(expected.getValue()).containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.starttls.required", "false")
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.sendpartial", "true")
                .containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "10000")
                .containsEntry("mail.smtp.writetimeout", "10000");
        assertThat(wire.commands()).contains("AUTH PLAIN ", "MAIL FROM:<" + SENDER + ">", "RCPT TO:<" + READER + ">");
        var submitted = wire.message();
        assertThat(submitted.getSubject()).isEqualTo(MAIL.subject());
        assertThat(submitted.isMimeType("text/html")).isTrue();
        assertThat(submitted.getContent()).isEqualTo(MAIL.html());
        assertThat(submitted.getFrom()).containsExactly(new InternetAddress(SENDER));
        assertThat(submitted.getRecipients(Message.RecipientType.TO)).singleElement().satisfies(address -> {
            var recipient = (InternetAddress) address;
            assertThat(recipient.getAddress()).isEqualTo(READER);
            assertThat(recipient.getPersonal()).isEqualTo("读者");
        });
    }

    @Test
    void refusesToAuthenticateWhenTheServerDoesNotOfferRequiredStartTls() throws Exception {
        var wire = new ScriptedSmtpSocket("""
                220 SMTP ready
                250-server
                250 AUTH PLAIN
                """);
        var expected = ArgumentCaptor.forClass(Properties.class);
        try (var sockets = mockStatic(SocketFetcher.class)) {
            sockets.when(() -> SocketFetcher.getSocket(eq(SMTP_HOST), eq(465), expected.capture(), eq("mail.smtp"), eq(false)))
                    .thenReturn(wire.socket);

            var result = transport.send(connection(Tls.STARTTLS), RECIPIENTS, MAIL);

            assertThat(result.status()).isEqualTo(Status.FAILED);
            assertThat(result.errorCode()).isEqualTo("MAIL_SMTP_CONNECT_FAILED");
        }
        assertThat(expected.getValue()).containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true");
        assertThat(wire.commands()).contains("EHLO").doesNotContain("AUTH", "MAIL FROM", "RCPT TO");
    }

    @ParameterizedTest
    @CsvSource({"550 recipient denied,FAILED,MAIL_SMTP_REJECTED", "250 recipient OK,UNKNOWN,MAIL_SMTP_SUBMISSION_UNKNOWN"})
    void distinguishesRejectedRecipientFromMissingFinalReply(String recipientReply, Status status, String code) throws Exception {
        String afterRecipient = status == Status.FAILED ? "250 reset\r\n" : "354 Send message\r\n";
        var wire = new ScriptedSmtpSocket(AUTHENTICATED + recipientReply + "\r\n" + afterRecipient);
        try (var sockets = mockStatic(SocketFetcher.class)) {
            sockets.when(() -> SocketFetcher.getSocket(eq(SMTP_HOST), eq(465), any(Properties.class), eq("mail.smtp"), eq(true)))
                    .thenReturn(wire.socket);

            var result = transport.send(connection(Tls.SSL), RECIPIENTS, MAIL);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.errorCode()).isEqualTo(code);
            if (status == Status.FAILED) {
                assertThat(result.recipientResults()).containsExactly(new RecipientResult(READER, Status.FAILED));
                assertThat(wire.commands()).doesNotContain("\r\nDATA\r\n");
            } else {
                assertThat(result.recipientResults()).isEmpty();
                assertThat(wire.commands()).contains("\r\nDATA\r\n", "\r\n.\r\n");
            }
        }
    }

    @Test
    void refusesMalformedEnvelopeBeforeOpeningASocket() {
        try (var sockets = mockStatic(SocketFetcher.class)) {
            var result = transport.send(connection(Tls.SSL), List.of(new Recipient("bad\r\nrecipient", null)), MAIL);

            assertThat(result.status()).isEqualTo(Status.FAILED);
            assertThat(result.errorCode()).isEqualTo("MAIL_SMTP_PREPARE_FAILED");
            assertThat(result.errorMessage()).doesNotContain("recipient");
            sockets.verifyNoInteractions();
        }
    }

    @Test
    void connectsToTheConfiguredImapServerInReadOnlyPeekMode() throws Exception {
        var session = mock(Session.class);
        var store = mock(Store.class);
        var inbox = mock(IMAPFolder.class);
        when(session.getStore("imap")).thenReturn(store);
        when(store.getFolder("INBOX")).thenReturn(inbox);
        when(inbox.getMessages()).thenReturn(new Message[0]);
        when(inbox.getUIDValidity()).thenReturn(41L);
        var properties = ArgumentCaptor.forClass(Properties.class);

        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.getInstance(properties.capture())).thenReturn(session);

            assertThat(transport.read(connection(Tls.STARTTLS), INBOX, null).messages()).isEmpty();
        }
        assertThat(properties.getValue()).containsEntry("mail.imap.peek", "true")
                .containsEntry("mail.imap.starttls.required", "true")
                .containsEntry("mail.imap.ssl.checkserveridentity", "true");
        verify(store).connect(IMAP_HOST, 993, "imap-user", "imap-password");
        verify(inbox).open(Folder.READ_ONLY);
        verify(inbox).close(false);
        verify(store).close();
    }

    @Test
    void returnsASanitizedErrorWhenImapAuthenticationFails() throws Exception {
        var session = mock(Session.class);
        var store = mock(Store.class);
        when(session.getStore("imap")).thenReturn(store);
        doThrow(new MessagingException("private imap-password reply"))
                .when(store).connect(IMAP_HOST, 993, "imap-user", "imap-password");
        Connection connection = connection(Tls.SSL);
        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);

            assertThatThrownBy(() -> transport.read(connection, INBOX, null))
                    .isInstanceOfSatisfying(MailTransportException.class, exception -> {
                        assertThat(exception.code()).isEqualTo("MAIL_IMAP_READ_FAILED");
                        assertThat(exception.getMessage()).doesNotContain("imap-password", "private");
                        assertThat(exception.getCause()).isNull();
                    });
        }
        verify(store).close();
    }

    private static Connection connection(Tls tls) {
        return new Connection(Provider.IMAP_SMTP, SENDER, new Settings(new Endpoint(IMAP_HOST, 993, "imap-user", tls),
                new Endpoint(SMTP_HOST, 465, "smtp-user", tls), null),
                new Credentials("imap-password", "smtp-password", null));
    }

    // The socket boundary substitutes the remote endpoint while Angus runs the SMTP state machine.
    private static final class ScriptedSmtpSocket {

        private final Socket socket = mock(Socket.class);
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        private ScriptedSmtpSocket(String replies) throws IOException {
            when(socket.getLocalAddress()).thenReturn(InetAddress.getLoopbackAddress());
            when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(replies.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(UTF_8)));
            when(socket.getOutputStream()).thenReturn(sent);
        }

        String commands() {
            return sent.toString(UTF_8);
        }

        MimeMessage message() throws MessagingException {
            String commands = commands();
            int start = commands.indexOf("\r\nDATA\r\n") + "\r\nDATA\r\n".length();
            int end = commands.indexOf("\r\n.\r\n", start);
            assertThat(end).isGreaterThan(start);
            return new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(commands.substring(start, end).getBytes(UTF_8)));
        }
    }
}
