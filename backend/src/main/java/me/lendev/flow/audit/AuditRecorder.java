package me.lendev.flow.audit;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.store.AuditStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;

/**
 * 审计事实记录器：在业务事务内写入成功事件，并在事务完成后补记回滚/未知结果的失败尝试。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditRecorder {

    private final AuditStore store;
    private final AuditEvents events;

    /**
     * 在业务事务内记录成功事件并加入 outbox 队列，与业务变更同事务提交。
     *
     * @param scope     审计调用作用域
     * @param operation 审计操作定义
     * @param details   已脱敏冻结的对象变更明细
     * @throws AuditUnavailableException 审计持久化失败时抛出，业务事务随之回滚
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void success(AuditScope scope, AuditOperation operation, List<AuditChange> details) {
        try {
            long id = store.enqueue(events.success(scope, operation, details));
            log.info("审计成功事件已入队，操作={} operationId={} 队列ID={} 变更对象数={}", operation.code(),
                    scope.operationId(), id, details.size());
        } catch (org.springframework.dao.DataAccessException failure) {
            log.warn("事务内审计入队失败，业务事务将回滚，请检查审计 outbox 表和数据库写入，requestId={} operationId={} operation={} errorType={}",
                    scope.requestId(), scope.operationId(), operation.code(), failure.getClass().getSimpleName());
            throw new AuditUnavailableException(failure);
        }
    }

    /**
     * 事务完成后补记失败尝试：仅处理回滚、结果未知或抛错的情形，提交成功的事实已由
     * {@link #success} 在事务内记录。失败尝试写入属于尽力而为，不影响主流程。
     *
     * @param scope     审计调用作用域
     * @param operation 审计操作定义
     * @param error     业务异常归类后的错误事实，无异常时为 {@code null}
     * @param status    事务完成状态，{@code null} 表示未参与事务同步
     */
    void completion(AuditScope scope, AuditOperation operation, AuditError error, Integer status) {
        // 提交成功的事实已由 success() 在事务内记录；这里仅处理回滚、结果未知或抛错的情形。
        if (status != null && status == TransactionSynchronization.STATUS_COMMITTED) {
            return;
        }
        if (status == null && error == null) {
            return;
        }
        boolean unknown = status != null && status == TransactionSynchronization.STATUS_UNKNOWN;
        try {
            store.recordAttempt(events.attempt(scope, operation, unknown, error));
            log.info("已补记审计失败尝试，操作={} operationId={} 结果={}", operation.code(), scope.operationId(),
                    unknown ? "UNKNOWN" : "FAILURE");
        } catch (RuntimeException failure) {
            // 失败尝试记录属于尽力而为，不能因审计写入异常反过来影响主流程。
            log.warn("审计失败尝试补记失败，主流程结果保留，请检查审计库写入并按操作 ID 补核审计事实，requestId={} operationId={} operation={} transactionStatus={} errorType={}",
                    scope.requestId(), scope.operationId(), operation.code(), status, failure.getClass().getSimpleName());
        }
    }
}
