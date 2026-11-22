package me.lendev.flow.pms.inapp;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.pms.inapp.mapper.InAppMessageMapper;
import me.lendev.flow.pms.inapp.mapper.InAppMessageMapper.NewMessage;
import me.lendev.flow.pms.inapp.mapper.InAppMessageMapper.Row;
import me.lendev.flow.workbench.flow.WorkflowNoticeAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接收业务通知并提供仅限本人使用的站内消息查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InAppMessageService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern SOURCE_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private final InAppMessageMapper messages;
    private final Clock clock;
    private final ObjectProvider<WorkflowNoticeAccess> workflowAccess;

    /**
     * 在调用方事务内保存站内消息。同一收件人和来源键重复提交时，返回原 ID 并保留原消息和已读时间。
     *
     * @param write 可信业务调用方生成的消息，含稳定来源键和可选结构化目标
     * @return 已持久化的消息 ID
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long publish(Write write) {
        validate(write);
        var row = new NewMessage(write.recipientUserId(), write.sourceModule(), write.sourceKey(), write.messageType(),
                write.title(), write.summary(), write.targetType(), write.targetId(), now());
        try {
            messages.insert(row);
            return row.getId();
        } catch (DuplicateKeyException duplicate) {
            Long existing = messages.existing(write.recipientUserId(), write.sourceModule(), write.sourceKey());
            if (existing == null) {
                throw duplicate;
            }
            return existing;
        }
    }

    /**
     * 按收件人和消息 ID 游标读取，过期或失去业务权限的目标不返回跳转。
     */
    public MessagePage page(int userId, Boolean unread, Long beforeId, int pageSize) {
        var rows = messages.page(userId, unread, beforeId, pageSize + 1);
        var visible = rows.subList(0, Math.min(pageSize, rows.size()));
        Long next = rows.size() > pageSize ? visible.getLast().id() : null;
        return new MessagePage(visible.stream().map(row -> item(userId, row)).toList(), next);
    }

    /**
     * 返回用户当前未读条数。
     */
    public long unread(int userId) {
        return messages.unread(userId);
    }

    /**
     * 幂等标记本人消息，保留第一次已读的时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markRead(int userId, long id) {
        if (messages.read(id, userId, now()) > 0) {
            log.info("站内消息标记已读，messageId={}", id);
            return true;
        }
        return messages.owned(id, userId) != null;
    }

    private Item item(int userId, Row row) {
        String target = null;
        if (row.targetId() != null) {
            var access = workflowAccess.getIfAvailable();
            if (access != null) {
                target = access.targetUrl(userId, row.targetType(), row.targetId());
            }
        }
        return new Item(row.id(), row.messageType(), row.title(), row.summary(), row.createdAt().atOffset(ZoneOffset.UTC),
                row.readAt() == null ? null : row.readAt().atOffset(ZoneOffset.UTC), target);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void validate(Write write) {
        if (write.recipientUserId() <= 0 || !matches(CODE, write.sourceModule()) || !matches(SOURCE_KEY, write.sourceKey())
                || !matches(CODE, write.messageType()) || write.title() == null || write.title().isBlank()
                || tooLong(write.title(), 200) || tooLong(write.summary(), 1000)
                || (write.targetType() == null) != (write.targetId() == null)
                || (write.targetType() != null && (!matches(CODE, write.targetType()) || write.targetId() <= 0))) {
            throw new IllegalArgumentException("站内消息参数不符合约定");
        }
    }

    private static boolean tooLong(String text, int limit) {
        return text != null && text.codePointCount(0, text.length()) > limit;
    }

    private static boolean matches(Pattern pattern, String text) {
        return text != null && pattern.matcher(text).matches();
    }

    /**
     * 业务模块写入消息的输入契约。
     */
    public record Write(int recipientUserId, String sourceModule, String sourceKey, String messageType,
                        String title, String summary, String targetType, Long targetId) {
    }

    public record MessagePage(List<Item> items, Long nextBeforeId) {
    }

    public record Item(long id, String messageType, String title, String summary, OffsetDateTime createdAt,
                       OffsetDateTime readAt, String targetUrl) {
    }
}
