package me.lendev.flow.audit;

import me.lendev.flow.audit.model.AuditActor;
import me.lendev.flow.audit.model.AuditScope;

/**
 * 审计上下文抽象：开启一次审计调用的作用域，并提供当前操作者身份。
 */
public interface AuditContext {

    /**
     * 开启一次审计调用的作用域。
     *
     * @param operation 本次调用的审计操作定义
     * @param args      入口方法的原始参数，仅用于提取安全的目标对象引用，不得留存
     * @return 冻结的调用作用域，供后续事件构建使用
     */
    AuditScope start(AuditOperation operation, Object[] args);

    /**
     * 返回当前已认证的操作者身份。
     *
     * @return 操作者快照；未认证请求返回匿名身份
     * @throws IllegalStateException 后台调用缺少显式系统身份或无法固定操作者时抛出
     */
    AuditActor currentActor();
}
