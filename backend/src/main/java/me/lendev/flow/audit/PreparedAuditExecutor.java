package me.lendev.flow.audit;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.common.auth.rate.TooManyRequestsException;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 外部操作审计执行器：先持久化意图再执行业务回调；审计重试只投递既有事实， 绝不会再次调用业务回调。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreparedAuditExecutor implements AuditExternalExecutor {

    private final AuditStore store;
    private final AuditEvents events;
    private final AuditContext context;

    /**
     * 执行一次外部操作审计：先在独立事务中持久化意图，再执行业务回调并确认结果。
     *
     * @param scope     审计调用作用域
     * @param operation 必须为 external 的操作定义
     * @param args      入口方法的原始参数
     * @param work      业务方法执行回调，只会被调用一次
     * @return 业务方法的原始返回值
     * @throws AuditUnavailableException 意图持久化失败时抛出，业务回调不会被执行
     * @throws Throwable                 业务回调抛出的任何异常均原样上抛
     */
    @Override
    public Object execute(AuditScope scope, AuditOperation operation, Object[] args, AuditHandler.Work work) throws Throwable {
        AuditEvent intent = events.intent(scope, operation);
        long id;
        try {
            id = store.prepare(intent);
        } catch (DataAccessException error) {
            log.warn("外部操作意图持久化失败，业务回调未执行，请检查审计 outbox 写入，requestId={} operationId={} operation={} errorType={}",
                    scope.requestId(), scope.operationId(), operation.code(), error.getClass().getSimpleName());
            throw new AuditUnavailableException(error);
        }
        log.info("外部操作意图已持久化，操作={} operationId={} 队列ID={}", operation.code(), scope.operationId(), id);

        return executePrepared(id, intent, scope, operation, work);
    }

    /**
     * 对已持久化的意图执行且仅执行一次业务回调；投递 Worker 永远不会调用本方法。
     *
     * @param id        outbox 中意图记录的主键
     * @param intent    已持久化的意图事件
     * @param scope     审计调用作用域
     * @param operation 审计操作定义
     * @param work      业务方法执行回调
     * @return 业务方法的原始返回值
     * @throws Throwable 业务回调抛出的任何异常均原样上抛
     */
    public Object executePrepared(long id, AuditEvent intent, AuditScope scope, AuditOperation operation,
                                  AuditHandler.Work work) throws Throwable {
        Prepared prepared = new Prepared(id, intent, scope, operation);
        Object result;
        try {
            result = work.run();
        } catch (Throwable error) {
            // 已知业务拒绝记为 FAILURE；无法确认结果的异常记为 UNKNOWN，等待超时恢复流程裁决。
            String outcome = knownRejection(error) ? "FAILURE" : "UNKNOWN";
            log.info("外部操作业务回调失败，操作={} operationId={} 结果={}", operation.code(), scope.operationId(), outcome);
            confirm(prepared, outcome, scope.actor(), AuditError.from(error), null);
            throw error;
        }

        // 身份查询与结果确认刻意放在业务 try/catch 之外：
        // 它们的失败不能把已经成功的登录/刷新变成业务失败。
        try {
            boolean challenge = result instanceof me.lendev.flow.pms.domain.dto.LoginTokenDto login
                    && login.getMfaChallenge() != null;
            boolean loginComplete = operation == AuditOperation.MFA_LOGIN || operation == AuditOperation.LOGIN && !challenge;
            AuditActor actor = loginComplete ? context.currentActor() : scope.actor();
            confirm(prepared, "SUCCESS", actor, null,
                    result instanceof AuditExternalMetadata metadata ? metadata : null);
        } catch (RuntimeException error) {
            pending(prepared, error);
        }
        return result;
    }

    private boolean knownRejection(Throwable error) {
        return error instanceof BizException || error instanceof BadRequestException
                || error instanceof me.lendev.flow.document.DocumentFailure
                || error instanceof TooManyRequestsException || error instanceof NotLoginException
                || error instanceof NotPermissionException;
    }

    private record Prepared(long id, AuditEvent intent, AuditScope scope, AuditOperation operation) {

    }

    private void confirm(Prepared prepared, String result, AuditActor actor, AuditError error, AuditExternalMetadata metadata) {
        try {
            AuditEvent outcome = events.outcome(prepared.scope(), prepared.operation(), result, actor, error)
                    .toBuilder().eventId(prepared.intent().eventId()).build();
            if (metadata != null) {
                outcome = outcome.toBuilder().objects(metadata.auditChanges()).summary(metadata.auditSummary()).build();
            }
            store.completePrepared(prepared.id(), outcome);
            log.info("外部操作结果已确认，操作={} operationId={} 队列ID={} 结果={}", prepared.operation().code(),
                    prepared.scope().operationId(), prepared.id(), result);
        } catch (RuntimeException failure) {
            pending(prepared, failure);
        }
    }

    private void pending(Prepared prepared, RuntimeException failure) {
        log.warn("外部操作审计结果未确认，队列将恢复超时意图，请核对外部系统实际结果和审计库写入，requestId={} operationId={} operation={} outboxId={} errorType={}",
                prepared.scope().requestId(), prepared.scope().operationId(), prepared.operation().code(),
                prepared.id(), failure.getClass().getSimpleName());
    }
}
