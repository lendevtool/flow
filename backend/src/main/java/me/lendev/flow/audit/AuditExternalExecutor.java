package me.lendev.flow.audit;

import me.lendev.flow.audit.model.AuditScope;

/**
 * 外部操作审计执行器接口：外部意图的执行独立于数据库变更模板实现。
 */
public interface AuditExternalExecutor {

    /**
     * 执行一次外部操作审计：先持久化意图，再驱动业务回调，最后确认结果。
     *
     * @param scope     审计调用作用域
     * @param operation 必须为 external 的操作定义
     * @param args      入口方法的原始参数
     * @param work      业务方法执行回调，只会被调用一次
     * @return 业务方法的原始返回值
     * @throws Throwable 业务回调抛出的任何异常均原样上抛
     */
    // 审计切面记录业务失败后必须原样传递 Throwable。
    @SuppressWarnings("java:S112")
    Object execute(AuditScope scope, AuditOperation operation, Object[] args, AuditHandler.Work work) throws Throwable;
}
