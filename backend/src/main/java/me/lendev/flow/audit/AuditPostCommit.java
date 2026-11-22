package me.lendev.flow.audit;

import java.time.Clock;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.audit.store.AuditStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 提交后审计步骤：在业务事务内持久化外部操作意图，事务提交后发起唯一一次外部尝试， 全程保留原始操作者身份。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditPostCommit {

    private final AuditStore store;
    private final AuditEvents events;
    private final PreparedAuditExecutor external;
    private final Clock clock;

    /**
     * 在当前数据库审计事务内登记外部操作意图，并注册提交后回调发起唯一一次外部尝试。
     *
     * <p>意图与业务变更同事务持久化：事务回滚则意图消失，外部步骤不会启动；
     * 事务提交后外部步骤失败仅记录日志，不抹杀已提交事实。
     *
     * @param operation 必须为 external 的操作定义
     * @param targets   外部步骤作用的审计对象列表
     * @param work      提交后执行的业务回调，只会被调用一次
     * @throws IllegalStateException     未处于数据库审计的可写事务中时抛出
     * @throws AuditUnavailableException 意图持久化失败时抛出，业务事务随之回滚
     */
    public void run(AuditOperation operation, List<AuditObject> targets, AuditHandler.Work work) {
        var root = AuditInvocation.CURRENT.get();
        if (root == null || root.operation.external() || !operation.external()
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("提交后审计步骤必须注册于数据库审计事务中");
        }
        var scope = root.scope.toBuilder().targets(targets).startedAt(clock.instant())
                .startedNanos(System.nanoTime()).build();
        var intent = events.intent(scope, operation);
        long id;
        try {
            id = store.prepareInTransaction(intent);
        } catch (DataAccessException failure) {
            log.warn("提交后外部意图登记失败，业务事务将回滚且外部步骤未启动，请检查审计 outbox 写入，requestId={} operationId={} operation={} errorType={}",
                    scope.requestId(), scope.operationId(), operation.code(), failure.getClass().getSimpleName());
            throw new AuditUnavailableException(failure);
        }
        log.info("提交后外部步骤意图已登记，操作={} operationId={} 队列ID={}", operation.code(), scope.operationId(), id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    log.info("业务事务已提交，开始执行外部步骤，操作={} operationId={}", operation.code(), scope.operationId());
                    external.executePrepared(id, intent, scope, operation, work);
                } catch (Throwable failure) {
                    // 数据库事务已经提交，清理失败不得抹杀"已提交"这一事实，因此仅记录日志。
                    log.warn("提交后外部步骤执行异常，业务已提交，请核对外部系统结果及审计记录后处置，requestId={} operationId={} operation={} outboxId={} errorType={}",
                            scope.requestId(), scope.operationId(), operation.code(), id, failure.getClass().getSimpleName());
                    // 仅 JVM 级致命错误继续上抛，其余失败不影响已完成的业务事务。
                    if (failure instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                }
            }
        });
    }
}
