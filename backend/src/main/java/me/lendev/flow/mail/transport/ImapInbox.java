package me.lendev.flow.mail.transport;

import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import me.lendev.flow.mail.MailModels.InboxMessage;
import me.lendev.flow.mail.MailModels.InboxPage;
import me.lendev.flow.mail.MailModels.InboxQuery;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

/**
 * 对 IMAP 收件箱执行只读搜索、精确过滤和稳定分页。
 */
final class ImapInbox {

    private static final int FETCH_BATCH_SIZE = 250;
    private static final int MAX_BODY_DEPTH = 32;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Comparator<Header> NEWEST_FIRST =
            Comparator.comparing((Header header) -> header.metadata().receivedAt())
                    .thenComparingLong(Header::uid).reversed();

    private ImapInbox() {
    }

    static InboxPage read(Store store, InboxQuery query, String providerCursor) throws MessagingException, IOException {
        Cursor cursor = Cursor.decode(providerCursor);
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            return readFolder(inbox, query, cursor);
        } finally {
            inbox.close(false);
        }
    }

    private static InboxPage readFolder(Folder inbox, InboxQuery query, Cursor cursor)
            throws MessagingException, IOException {
        UIDFolder uids = (UIDFolder) inbox;
        long validity = uids.getUIDValidity();
        if (cursor != null && cursor.validity() != validity) {
            throw MailTransportException.invalidCursor();
        }
        SearchTerm search = candidateSearch(query, cursor);
        Message[] candidates = search == null ? inbox.getMessages() : inbox.search(search);
        TreeSet<Header> matches = selectHeaders(inbox, uids, validity, candidates, query, cursor);

        String nextCursor = null;
        if (matches.size() > query.pageSize()) {
            matches.pollLast();
            Header last = matches.last();
            nextCursor = new Cursor(validity, last.metadata().receivedAt(), last.uid()).encode();
        }
        List<InboxMessage> messages = new ArrayList<>(matches.size());
        for (Header header : matches) {
            messages.add(query.includeBody() ? addBody(header) : header.metadata());
        }
        return new InboxPage(List.copyOf(messages), nextCursor);
    }

    /**
     * IMAP 日期搜索按天比较。扩展候选范围以覆盖服务端和 JVM 的时区差，
     * 之后使用邮件的 INTERNALDATE 时间戳精确检查起止边界。
     */
    private static SearchTerm candidateSearch(InboxQuery query, Cursor cursor) {
        List<SearchTerm> terms = new ArrayList<>();
        if (query.receivedFrom() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE,
                    Date.from(query.receivedFrom().minus(2, ChronoUnit.DAYS))));
        }
        Instant upper = query.receivedTo();
        if (cursor != null && (upper == null || cursor.receivedAt().isBefore(upper))) {
            upper = cursor.receivedAt();
        }
        if (upper != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.LT, Date.from(upper.plus(2, ChronoUnit.DAYS))));
        }
        if (query.read() != null) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), query.read().booleanValue()));
        }
        return terms.isEmpty() ? null : new AndTerm(terms.toArray(SearchTerm[]::new));
    }

    private static TreeSet<Header> selectHeaders(Folder inbox, UIDFolder uids, long validity, Message[] candidates,
                                                 InboxQuery query, Cursor cursor) throws MessagingException {
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        profile.add(UIDFolder.FetchProfileItem.UID);
        TreeSet<Header> matches = new TreeSet<>(NEWEST_FIRST);
        for (int start = 0; start < candidates.length; start += FETCH_BATCH_SIZE) {
            Message[] batch = Arrays.copyOfRange(candidates, start, Math.min(start + FETCH_BATCH_SIZE, candidates.length));
            inbox.fetch(batch, profile);
            for (Message candidate : batch) {
                Header header = readHeader(uids, validity, candidate);
                if (header == null || !InboxFilters.matches(query, header.metadata())
                        || cursor != null && !cursor.precedes(header)) {
                    continue;
                }
                matches.add(header);
                if (matches.size() > query.pageSize() + 1) {
                    matches.pollLast();
                }
            }
        }
        return matches;
    }

    private static Header readHeader(UIDFolder uids, long validity, Message message) throws MessagingException {
        try {
            long uid = uids.getUID(message);
            Date received = message.getReceivedDate();
            if (uid <= 0 || received == null) {
                return null;
            }
            Address[] from = message.getFrom();
            InternetAddress sender = from != null && from.length > 0 && from[0] instanceof InternetAddress address
                    ? address : null;
            InboxMessage metadata = new InboxMessage(validity + ":" + uid, sender == null ? null : sender.getAddress(),
                    sender == null ? null : sender.getPersonal(), message.getSubject(), received.toInstant(),
                    message.isSet(Flags.Flag.SEEN), null, null);
            return new Header(uid, metadata, message);
        } catch (MessageRemovedException exception) {
            // 收件箱在查询期间可能被其他客户端修改，已移除的邮件不进入本页。
            return null;
        }
    }

    private static InboxMessage addBody(Header header) throws MessagingException, IOException {
        Body body = findBody(header.message(), 0);
        InboxMessage metadata = header.metadata();
        if (body == null) {
            return metadata;
        }
        return new InboxMessage(metadata.id(), metadata.sender(), metadata.senderName(), metadata.subject(),
                metadata.receivedAt(), metadata.read(), body.content(), body.format());
    }

    private static Body findBody(Part part, int depth) throws MessagingException, IOException {
        if (depth > MAX_BODY_DEPTH || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            return null;
        }
        if (part.isMimeType("text/html")) {
            return readTextBody(part, "HTML");
        }
        if (part.isMimeType("text/plain")) {
            return readTextBody(part, "TEXT");
        }
        if (!part.isMimeType("multipart/*") || !(part.getContent() instanceof Multipart parts)) {
            return null;
        }
        return findMultipartBody(parts, part.isMimeType("multipart/alternative"), depth + 1);
    }

    private static Body readTextBody(Part part, String format) throws MessagingException, IOException {
        String charset = new ContentType(part.getContentType()).getParameter("charset");
        String encoding = MimeUtility.javaCharset(charset == null ? "us-ascii" : charset);
        // 在构造字符串之前限制传输编码解码后的字节数，超限时仍可选择其他正文分段。
        try (var content = part.getInputStream()) {
            byte[] bytes = content.readNBytes(MAX_BODY_BYTES + 1);
            return bytes.length > MAX_BODY_BYTES ? null : new Body(new String(bytes, encoding), format);
        }
    }

    private static Body findMultipartBody(Multipart parts, boolean alternative, int depth) throws MessagingException, IOException {
        Body plain = null;
        for (int i = 0; i < parts.getCount(); i++) {
            Body body = findBody(parts.getBodyPart(i), depth);
            if (body == null) {
                continue;
            }
            if (!alternative || "HTML".equals(body.format())) {
                return body;
            }
            plain = body;
        }
        return plain;
    }

    private record Header(long uid, InboxMessage metadata, Message message) {
    }

    private record Body(String content, String format) {
    }

    private record Cursor(long validity, Instant receivedAt, long uid) {

        boolean precedes(Header header) {
            int timeComparison = receivedAt.compareTo(header.metadata().receivedAt());
            return timeComparison > 0 || timeComparison == 0 && uid > header.uid();
        }

        String encode() {
            return validity + "\n" + receivedAt + "\n" + uid;
        }

        static Cursor decode(String raw) {
            if (raw == null) {
                return null;
            }
            String[] parts = raw.split("\n", -1);
            if (parts.length != 3) {
                throw MailTransportException.invalidCursor();
            }
            try {
                long validity = Long.parseLong(parts[0]);
                long uid = Long.parseLong(parts[2]);
                if (validity <= 0 || uid <= 0) {
                    throw MailTransportException.invalidCursor();
                }
                return new Cursor(validity, Instant.parse(parts[1]), uid);
            } catch (NumberFormatException | DateTimeParseException exception) {
                throw MailTransportException.invalidCursor();
            }
        }
    }
}
