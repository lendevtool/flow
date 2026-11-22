package me.lendev.flow.audit;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.model.*;
import org.springframework.stereotype.Component;

/**
 * 审计事件工厂：基于调用作用域与操作定义，构建成功、意图、结果、失败尝试等各阶段的不可变 {@code AuditEvent}。
 */
@Component
@RequiredArgsConstructor
public class AuditEvents {

    private final Clock clock;
    private final AuditSnapshots snapshots;

    /**
     * 构建数据库提交阶段的完成事件；无任何字段变化时结果记为 NO_CHANGE。
     *
     * @param scope     审计调用作用域
     * @param operation 审计操作定义
     * @param changes   已脱敏冻结的对象变更明细
     * @return DATABASE_COMMIT 阶段的完成事件
     */
    public AuditEvent success(AuditScope scope, AuditOperation operation, List<AuditChange> changes) {
        boolean changed = changes.stream().anyMatch(AuditChange::changed);
        String result = changed || operation == AuditOperation.AUDIT_RETRY
                || operation == AuditOperation.USER_PASSWORD_RESET || operation == AuditOperation.USER_PASSWORD_CHANGE ? "SUCCESS" : "NO_CHANGE";
        return base(scope, operation).phase("DATABASE_COMMIT").result(result)
                .summary(summary(operation, changes.stream().map(AuditChange::object).toList())).objects(changes).build();
    }

    /**
     * 构建外部操作的意图事件：结果暂记 UNKNOWN，等待结果确认后改写。
     *
     * @param scope     审计调用作用域
     * @param operation 必须为 external 的操作定义
     * @return EXTERNAL_RESULT 阶段的意图事件，目标对象仅含引用
     */
    public AuditEvent intent(AuditScope scope, AuditOperation operation) {
        return base(scope, operation).phase("EXTERNAL_RESULT").durationMs(null).result("UNKNOWN")
                .objects(scope.targets().stream().map(snapshots::reference).toList()).build();
    }

    /**
     * 构建外部操作的最终结果事件；{@code error} 非空时附带脱敏后的错误事实。
     *
     * @param scope     审计调用作用域
     * @param operation 审计操作定义
     * @param result    结果码（SUCCESS/FAILURE/UNKNOWN 等）
     * @param actor     结果确认时固定的操作者身份
     * @param error     错误事实，成功时为 {@code null}
     * @return EXTERNAL_RESULT 阶段的结果事件
     */
    public AuditEvent outcome(AuditScope scope, AuditOperation operation, String result,
                              AuditActor actor, AuditError error) {
        var event = base(scope, operation).phase("EXTERNAL_RESULT").actor(actor).result(result)
                .objects(scope.targets().stream().map(snapshots::reference).toList());
        if (error != null) {
            event.errorCode(error.code()).errorMessage(error.message()).businessCode(error.businessCode());
        }
        return event.build();
    }

    /**
     * 构建事务完成后的失败尝试事件。
     *
     * @param scope     审计调用作用域
     * @param operation 审计操作定义
     * @param unknown   事务结果无法确认时为 true，结果记 UNKNOWN；否则按 {@code error}
     *                  或回滚事实记录 FAILURE
     * @param error     错误事实，无异常时为 {@code null}
     * @return ATTEMPT 阶段的失败尝试事件
     */
    public AuditEvent attempt(AuditScope scope, AuditOperation operation, boolean unknown, AuditError error) {
        var event = base(scope, operation).phase("ATTEMPT").result(unknown ? "UNKNOWN" : "FAILURE")
                .summary(summary(operation, scope.targets()))
                .objects(scope.targets().stream().map(snapshots::reference).toList());
        if (unknown) {
            event.durationMs(null).errorCode("COMMIT_UNKNOWN").errorMessage("事务结果无法确认");
        } else if (error != null) {
            event.errorCode(error.code()).errorMessage(error.message()).businessCode(error.businessCode());
        } else {
            event.errorCode("TRANSACTION_ROLLED_BACK").errorMessage("所属事务已回滚，变更未提交");
        }
        return event.build();
    }

    private AuditEvent.AuditEventBuilder base(AuditScope scope, AuditOperation operation) {
        return AuditEvent.builder().eventId(UUID.randomUUID().toString()).operationId(scope.operationId())
                .requestId(scope.requestId()).traceId(scope.traceId()).schemaVersion(1)
                .startedAt(scope.startedAt().truncatedTo(ChronoUnit.MILLIS))
                .occurredAt(clock.instant().truncatedTo(ChronoUnit.MILLIS))
                .durationMs(Math.max(0L, (System.nanoTime() - scope.startedNanos()) / 1_000_000))
                .actor(scope.actor()).claimedAccount(scope.claimedAccount()).moduleCode(operation.module())
                .operationCode(operation.code()).action(operation.action()).summary(operation.label())
                .sourceIp(scope.sourceIp()).requestMethod(scope.requestMethod()).requestPath(scope.requestPath())
                .sourceType(scope.sourceType());
    }

    private String summary(AuditOperation operation, List<AuditObject> objects) {
        if (objects.isEmpty()) {
            return operation.label();
        }
        String names = objects.stream().limit(3).map(object -> object.objectName()
                        + (object.objectCode() == null ? "" : "（" + object.objectCode() + "）"))
                .collect(java.util.stream.Collectors.joining("、"));
        return RequestAuditContext.clean(operation.label() + "：" + names
                + (objects.size() > 3 ? "等" + objects.size() + "个对象" : ""), 1000);
    }
}
