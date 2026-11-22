package me.lendev.flow.audit.query;

import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.query.mapper.AuditQueryMapper;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

/**
 * {@code AUDIT_RETRY} 操作的审计处理器：以行级锁捕获人工重试前后 outbox 记录的状态变化。
 */
@Component
@RequiredArgsConstructor
public class AuditAdminHandler implements AuditHandler {

    private final AuditQueryMapper queries;
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.AUDIT_RETRY);
    }

    /**
     * 准备人工重试的变更提取计划：校验队列 ID 并以行级锁捕获重试前快照，
     * 业务方法返回后再次捕获快照生成前后对比。
     *
     * @param operation 审计操作，固定为 {@code AUDIT_RETRY}
     * @param args      入口参数，args[0] 为 outbox 记录主键
     * @return 变更提取计划
     * @throws BadRequestException 队列 ID 非法或记录不存在时抛出
     */
    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        Long id = (Long) args[0];
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.audit.queue.id.invalid");
        }
        var before = capture(id);
        return result -> List.of(snapshots.updated(before, capture(id)));
    }

    /**
     * 以行级锁读取 outbox 记录并冻结投递状态、领取次数、失败次数与人工重试次数快照。
     */
    private AuditSnapshots.Capture capture(Long id) {
        var row = queries.lockQueueSnapshot(id);
        if (row == null) {
            throw BadRequestException.localized("error.audit.queue.missing");
        }
        var ref = new AuditObject("AUDIT_OUTBOX", id.toString(), row.getEventId(), row.getOperationCode(), null, null);
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("status", AuditField.visible("投递状态", row.getStatus()));
        fields.put("attempts", AuditField.visible("累计领取次数", row.getAttempts()));
        fields.put("failureCount", AuditField.visible("本轮失败次数", row.getFailureCount()));
        fields.put("manualRetryCount", AuditField.visible("人工重试次数", row.getManualRetryCount()));
        return snapshots.capture(ref, fields);
    }
}
