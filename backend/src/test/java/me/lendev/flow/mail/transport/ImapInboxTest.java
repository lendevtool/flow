package me.lendev.flow.mail.transport;

import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.util.ByteArrayDataSource;
import me.lendev.flow.mail.MailModels.InboxMessage;
import me.lendev.flow.mail.MailModels.InboxQuery;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImapInboxTest {

    private final Store store = mock(Store.class);
    private final IMAPFolder folder = mock(IMAPFolder.class);
    private final Session session = Session.getInstance(new Properties());

    @BeforeEach
    void identifyInbox() throws Exception {
        when(store.getFolder("INBOX")).thenReturn(folder);
        when(folder.getUIDValidity()).thenReturn(100L);
    }

    @Test
    void appliesExactBoundariesAndKeepsStableReadOnlyPagination() throws Exception {
        var atEnd = message(15, "2026-01-01T12:00:30Z", "New Status", "sender@example.com", false);
        var sameTimeLowerUid = message(9, "2026-01-01T12:00:20Z", "new STATUS", "sender@example.com", false);
        var wrongSender = message(13, "2026-01-01T12:00:21Z", "New Status", "other-sender@example.com", false);
        var sameTimeHigherUid = message(12, "2026-01-01T12:00:20Z", "New Status", "SENDER@example.com", false);
        var wrongRead = message(14, "2026-01-01T12:00:25Z", "New Status", "sender@example.com", true);
        var wrongSubject = message(2, "2026-01-01T12:00:19Z", "Other", "sender@example.com", false);
        var atStart = message(1, "2026-01-01T12:00:10Z", "New Status", "sender@example.com", false);
        var beforeStart = message(3, "2026-01-01T12:00:09.999Z", "New Status", "sender@example.com", false);
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[]{
                atEnd, sameTimeLowerUid, wrongSender, sameTimeHigherUid, wrongRead, wrongSubject, atStart, beforeStart
        });
        var query = new InboxQuery("sender@example.com", "status", Instant.parse("2026-01-01T12:00:10Z"),
                Instant.parse("2026-01-01T12:00:30Z"), false, true, 2);

        var first = ImapInbox.read(store, query, null);
        verify(folder).open(Folder.READ_ONLY);
        verify(folder).close(false);
        assertThat(first.messages()).extracting(InboxMessage::id).containsExactly("100:12", "100:9");
        assertThat(first.messages().getFirst().body()).isEqualTo("<p>Body</p>");
        assertThat(first.messages().getFirst().bodyFormat()).isEqualTo("HTML");
        assertThat(first.messages()).allSatisfy(message -> assertThat(message.read()).isFalse());
        assertThat(sameTimeHigherUid.isSet(Flags.Flag.SEEN)).isFalse();
        assertThat(first.nextCursor()).isNotBlank();

        var second = ImapInbox.read(store, query, first.nextCursor());
        assertThat(second.messages()).extracting(InboxMessage::id).containsExactly("100:1");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void rejectsAnOldCursorWhenTheMailboxUidValidityChanges() throws Exception {
        var query = new InboxQuery(null, null, null, null, null, false, 1);
        var newest = message(12, "2026-01-01T12:00:20Z", "New Status", "sender@example.com", false);
        var older = message(7, "2026-01-01T12:00:10Z", "Old Status", "sender@example.com", false);
        when(folder.getMessages()).thenReturn(new Message[]{newest, older});

        var initial = ImapInbox.read(store, query, null);
        assertThat(initial.messages().getFirst().body()).isNull();
        assertThat(initial.messages().getFirst().bodyFormat()).isNull();
        when(folder.getUIDValidity()).thenReturn(101L);
        String cursor = initial.nextCursor();

        assertThatThrownBy(() -> ImapInbox.read(store, query, cursor))
                .isInstanceOfSatisfying(MailTransportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MAIL_INBOX_CURSOR_INVALID"));
    }

    @Test
    void returnsThePlainAlternativeWithoutOpeningAttachmentsAndKeepsUnsupportedPartsOutOfTheBody() throws Exception {
        var plainOnly = message(4, "2026-01-01T12:00:40Z", "Plain", "sender@example.com", false);
        var attachment = new MimeBodyPart();
        attachment.setText("<p>not the body</p>", "UTF-8", "html");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("attachment.html");
        var plain = new MimeBodyPart();
        plain.setText("Plain message");
        var multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(attachment);
        multipart.addBodyPart(plain);
        plainOnly.setContent(multipart);
        plainOnly.saveChanges();

        var unsupported = message(3, "2026-01-01T12:00:30Z", "Opaque", "sender@example.com", false);
        unsupported.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{17}, "application/octet-stream")));
        unsupported.saveChanges();
        var older = message(2, "2026-01-01T12:00:20Z", "Older", "sender@example.com", false);
        var oldest = message(1, "2026-01-01T12:00:10Z", "Oldest", "sender@example.com", false);
        when(folder.getMessages()).thenReturn(new Message[]{oldest, older, unsupported, plainOnly});
        var query = new InboxQuery(null, null, null, null, null, true, 2);

        var page = ImapInbox.read(store, query, null);

        assertThat(page.messages()).extracting(InboxMessage::id).containsExactly("100:4", "100:3");
        assertThat(page.messages().getFirst().body()).isEqualTo("Plain message");
        assertThat(page.messages().getFirst().bodyFormat()).isEqualTo("TEXT");
        assertThat(page.messages().getLast().body()).isNull();
        assertThat(page.messages().getLast().bodyFormat()).isNull();
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void skipsExpungedMessagesAndCandidatesWithoutAStableUid() throws Exception {
        var stable = message(4, "2026-01-01T12:00:40Z", "Stable", "sender@example.com", false);
        var removed = message(5, "2026-01-01T12:00:50Z", "Removed", "sender@example.com", false);
        when(removed.getReceivedDate()).thenThrow(new MessageRemovedException());
        var missingUid = message(-1, "2026-01-01T12:00:55Z", "No UID", "sender@example.com", false);
        when(folder.getMessages()).thenReturn(new Message[]{missingUid, removed, stable});
        var query = new InboxQuery(null, null, null, null, null, false, 5);

        var page = ImapInbox.read(store, query, null);

        assertThat(page.messages()).extracting(InboxMessage::id).containsExactly("100:4");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void retainsMetadataAndSkipsAnExcessivelyNestedBody() throws Exception {
        var nested = new MimeBodyPart();
        nested.setText("Nested text");
        for (int index = 0; index < 40; index++) {
            var multipart = new MimeMultipart("mixed");
            multipart.addBodyPart(nested);
            nested = new MimeBodyPart();
            nested.setContent(multipart);
        }
        var root = new MimeMultipart("mixed");
        root.addBodyPart(nested);
        var mail = message(6, "2026-01-01T12:00:00Z", "Nested", "sender@example.com", false);
        mail.setContent(root);
        mail.saveChanges();
        when(folder.getMessages()).thenReturn(new Message[]{mail});
        var query = new InboxQuery(null, null, null, null, null, true, 1);

        var page = ImapInbox.read(store, query, null);

        assertThat(page.messages()).singleElement().satisfies(message -> {
            assertThat(message.subject()).isEqualTo("Nested");
            assertThat(message.body()).isNull();
            assertThat(message.bodyFormat()).isNull();
        });
        verify(folder).close(false);
    }

    @Test
    void limitsDecodedTextAndKeepsOtherAlternativesAndMessageMetadata() throws Exception {
        var oversized = "A".repeat(1024 * 1024 + 1);
        var plain = new MimeBodyPart();
        plain.setText("Résumé", "ISO-8859-1");
        var html = new MimeBodyPart();
        html.setText(oversized, "UTF-8", "html");
        var alternatives = new MimeMultipart("alternative");
        alternatives.addBodyPart(plain);
        alternatives.addBodyPart(html);
        var withAlternative = message(3, "2026-01-01T12:00:30Z", "Alternative", "sender@example.com", false);
        withAlternative.setContent(alternatives);
        withAlternative.saveChanges();

        var withoutAlternative = message(2, "2026-01-01T12:00:20Z", "Summary", "sender@example.com", false);
        withoutAlternative.setText(oversized, "UTF-8");
        withoutAlternative.saveChanges();
        var exactLimit = message(1, "2026-01-01T12:00:10Z", "Exact", "sender@example.com", false);
        byte[] body = "é".repeat(512 * 1024).getBytes(StandardCharsets.UTF_8);
        exactLimit.setDataHandler(new DataHandler(new ByteArrayDataSource(body, "text/plain; charset=UTF-8")));
        exactLimit.saveChanges();
        when(folder.getMessages()).thenReturn(new Message[]{withAlternative, withoutAlternative, exactLimit});
        var query = new InboxQuery(null, null, null, null, null, true, 3);

        var page = ImapInbox.read(store, query, null);

        assertThat(page.messages()).extracting(InboxMessage::subject).containsExactly("Alternative", "Summary", "Exact");
        assertThat(page.messages().get(0)).satisfies(message -> {
            assertThat(message.body()).isEqualTo("Résumé");
            assertThat(message.bodyFormat()).isEqualTo("TEXT");
        });
        assertThat(page.messages().get(1)).satisfies(message -> {
            assertThat(message.body()).isNull();
            assertThat(message.bodyFormat()).isNull();
        });
        assertThat(page.messages().get(2).body()).hasSize(512 * 1024).startsWith("é").endsWith("é");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "100\n2026-01-01T12:00:10Z\n0", "100\nnot-a-date\n1"})
    void rejectsMalformedCursorsBeforeOpeningTheMailbox(String cursor) {
        var query = new InboxQuery(null, null, null, null, null, false, 5);

        assertThatThrownBy(() -> ImapInbox.read(store, query, cursor))
                .isInstanceOfSatisfying(MailTransportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MAIL_INBOX_CURSOR_INVALID"));
        verifyNoInteractions(store);
    }

    private MimeMessage message(long uid, String timestamp, String subject, String sender, boolean read) throws Exception {
        MimeMessage message = spy(new MimeMessage(session));
        message.setSubject(subject);
        message.setFrom(new InternetAddress(sender, "Sender"));

        var plain = new MimeBodyPart();
        plain.setText("Body");
        var html = new MimeBodyPart();
        html.setText("<p>Body</p>", "UTF-8", "html");
        var alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);
        message.setContent(alternative);
        message.setFlag(Flags.Flag.SEEN, read);
        message.saveChanges();

        when(message.getReceivedDate()).thenReturn(Date.from(Instant.parse(timestamp)));
        when(folder.getUID(message)).thenReturn(uid);
        return message;
    }
}
