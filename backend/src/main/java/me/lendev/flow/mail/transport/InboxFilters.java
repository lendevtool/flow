package me.lendev.flow.mail.transport;

import me.lendev.flow.mail.MailModels.InboxMessage;
import me.lendev.flow.mail.MailModels.InboxQuery;

import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;

/**
 * 两种接入协议共用的精确收件箱筛选规则。
 */
final class InboxFilters {

    static final Comparator<InboxMessage> NEWEST_FIRST =
            Comparator.comparing(InboxMessage::receivedAt).thenComparing(InboxMessage::id).reversed();

    private InboxFilters() {
    }

    static boolean matches(InboxQuery query, InboxMessage message) {
        Instant receivedAt = message.receivedAt();
        return (query.sender() == null || query.sender().equalsIgnoreCase(message.sender()))
                && (query.subject() == null || message.subject() != null && containsIgnoreCase(message.subject(), query.subject()))
                && (query.receivedFrom() == null || !receivedAt.isBefore(query.receivedFrom()))
                && (query.receivedTo() == null || receivedAt.isBefore(query.receivedTo()))
                && (query.read() == null || query.read().booleanValue() == message.read());
    }

    private static boolean containsIgnoreCase(String value, String search) {
        return value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }
}
