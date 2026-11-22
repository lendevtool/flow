package me.lendev.flow.mail.transport;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import me.lendev.flow.mail.MailModels.Connection;
import me.lendev.flow.mail.MailModels.Endpoint;
import me.lendev.flow.mail.MailModels.InboxPage;
import me.lendev.flow.mail.MailModels.InboxQuery;
import me.lendev.flow.mail.MailModels.Provider;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RenderedMail;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailModels.Submission;
import me.lendev.flow.mail.MailModels.Tls;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 通过 TLS 访问 IMAP 收件箱，并通过认证的 SMTP 连接提交邮件。
 */
@Component
final class ImapSmtpMailTransport implements MailTransport {

    private static final String CONNECT_TIMEOUT_MS = "5000";
    private static final String IO_TIMEOUT_MS = "10000";
    private static final String DISABLED = "false";

    @Override
    public Provider provider() {
        return Provider.IMAP_SMTP;
    }

    @Override
    public InboxPage read(Connection connection, InboxQuery query, String providerCursor) {
        Endpoint endpoint = connection.settings().imap();
        Session session = mailSession("imap", endpoint.tls());
        try (var store = session.getStore("imap")) {
            store.connect(endpoint.host(), endpoint.port(), endpoint.username(), connection.credentials().imapPassword());
            return ImapInbox.read(store, query, providerCursor);
        } catch (MessagingException | IOException | IllegalArgumentException exception) {
            throw new MailTransportException("MAIL_IMAP_READ_FAILED", "无法读取 IMAP 收件箱");
        }
    }

    @Override
    public Submission send(Connection connection, List<Recipient> recipients, RenderedMail mail) {
        Endpoint endpoint = connection.settings().smtp();
        Session session = mailSession("smtp", endpoint.tls());
        MimeMessage message;
        Address[] addresses;
        try {
            message = new MimeMessage(session);
            message.setFrom(new InternetAddress(connection.mailboxAddress(), true));
            addresses = recipientAddresses(recipients);
            message.setRecipients(Message.RecipientType.TO, addresses);
            message.setSubject(mail.subject(), UTF_8.name());
            message.setText(mail.html(), UTF_8.name(), "html");
            message.saveChanges();
        } catch (MessagingException | UnsupportedEncodingException exception) {
            return new Submission(Status.FAILED, null, "MAIL_SMTP_PREPARE_FAILED", "无法生成 SMTP 邮件", List.of());
        }
        var transport = new SubmissionTransport(session);
        try {
            transport.connect(endpoint.host(), endpoint.port(), endpoint.username(), connection.credentials().smtpPassword());
            return submit(transport, message, addresses, recipients);
        } catch (MessagingException exception) {
            return new Submission(Status.FAILED, null, "MAIL_SMTP_CONNECT_FAILED", "SMTP 连接或认证失败", List.of());
        } finally {
            close(transport);
        }
    }

    private static Address[] recipientAddresses(List<Recipient> recipients)
            throws MessagingException, UnsupportedEncodingException {
        Address[] addresses = new Address[recipients.size()];
        for (int i = 0; i < recipients.size(); i++) {
            Recipient recipient = recipients.get(i);
            var address = new InternetAddress(recipient.address(), true);
            if (recipient.name() != null) {
                address.setPersonal(recipient.name(), UTF_8.name());
            }
            addresses[i] = address;
        }
        return addresses;
    }

    private static Submission submit(SubmissionTransport transport, MimeMessage message, Address[] addresses,
                                     List<Recipient> recipients) {
        try {
            transport.sendMessage(message, addresses);
            return new Submission(Status.ACCEPTED, null, null, null, List.of());
        } catch (MessagingException exception) {
            return SmtpSubmission.failure(recipients, exception, transport.commitStarted());
        }
    }

    private static void close(SubmissionTransport transport) {
        try {
            transport.close();
        } catch (MessagingException ignored) {
            // 关闭会话失败时保留 SMTP 提交阶段已确认的结果。
        }
    }

    private static Session mailSession(String protocol, Tls tls) {
        Properties properties = new Properties();
        String prefix = "mail." + protocol + ".";
        properties.setProperty(prefix + "connectiontimeout", CONNECT_TIMEOUT_MS);
        properties.setProperty(prefix + "timeout", IO_TIMEOUT_MS);
        properties.setProperty(prefix + "writetimeout", IO_TIMEOUT_MS);
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.setProperty(prefix + "ssl.enable", Boolean.toString(tls == Tls.SSL));
        properties.setProperty(prefix + "starttls.enable", Boolean.toString(tls == Tls.STARTTLS));
        properties.setProperty(prefix + "starttls.required", Boolean.toString(tls == Tls.STARTTLS));
        properties.setProperty("mail.imap.peek", "true");
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.sendpartial", "true");
        properties.setProperty("mail.smtp.quitwait", DISABLED);
        properties.setProperty("mail.debug", DISABLED);
        properties.setProperty("mail.debug.auth.username", DISABLED);
        return Session.getInstance(properties);
    }

    /**
     * 记录 SMTP DATA 或 BDAT 的提交边界，用于识别可能已经受理的发送。
     */
    private static final class SubmissionTransport extends SMTPTransport {

        private boolean commitStarted;

        private SubmissionTransport(Session session) {
            super(session, new URLName("smtp", null, -1, null, null, null));
        }

        private boolean commitStarted() {
            return commitStarted;
        }

        @Override
        protected void finishData() throws IOException, MessagingException {
            commitStarted = true;
            super.finishData();
        }

        @Override
        protected void finishBdat() throws IOException, MessagingException {
            commitStarted = true;
            super.finishBdat();
        }
    }
}
