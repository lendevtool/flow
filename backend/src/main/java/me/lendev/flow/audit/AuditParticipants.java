package me.lendev.flow.audit;

import java.util.Collection;

import me.lendev.flow.audit.model.AuditChange;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 同事务业务参与者将已冻结的变更加入调用方的聚合审计。
 */
public final class AuditParticipants {

    private AuditParticipants() {
    }

    /**
     * @return 当前线程是否已经进入审计边界（含 external 与数据库操作）
     */
    public static boolean inProgress() {
        return AuditInvocation.CURRENT.get() != null;
    }

    /**
     * @return 当前审计调用的 HTTP 请求 ID，供应用日志关联；不在审计边界内或后台调用返回 {@code null}
     */
    public static String currentRequestId() {
        var state = AuditInvocation.CURRENT.get();
        return state == null ? null : state.scope.requestId();
    }

    /**
     * @return 当前是否处于可接收参与对象的状态：已进入审计边界、操作为数据库模式且处于可写事务中
     */
    public static boolean active() {
        var state = AuditInvocation.CURRENT.get();
        return state != null && !state.operation.external()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    /**
     * 返回当前数据库审计的操作 ID，用于关联稍后执行的外部步骤。
     *
     * @return 当前调用的操作 ID
     * @throws IllegalStateException 不处于可写审计事务中时抛出
     */
    public static String operationId() {
        if (!active()) {
            throw new IllegalStateException("关联外部步骤需要已有的可写审计事务");
        }
        return AuditInvocation.CURRENT.get().scope.operationId();
    }

    /**
     * 将业务参与者的对象变更附加到当前数据库审计事件，随主操作一并提交。
     *
     * @param changes 已脱敏冻结的对象变更集合
     * @throws IllegalStateException 不处于可写审计事务中时抛出
     */
    public static void addChanges(Collection<AuditChange> changes) {
        if (!active()) {
            throw new IllegalStateException("审计参与者需要已有的可写审计事务");
        }
        AuditInvocation.CURRENT.get().related.addAll(changes);
    }
}
