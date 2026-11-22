package me.lendev.flow.audit;

import java.util.List;
import java.util.Set;

import me.lendev.flow.audit.model.AuditChange;

/**
 * 数据库审计处理器 SPI：声明可处理的操作集合，并在启动期与运行期完成参数/返回值绑定校验与变更提取。
 */
public interface AuditHandler {

    /**
     * @return 本处理器负责的数据库审计操作集合，启动期校验一一对应且不重复
     */
    Set<AuditOperation> operations();

    /**
     * 业务方法执行前的准备：校验参数并捕获变更前快照。
     *
     * @param operation 本次审计操作
     * @param args      入口方法的原始参数，仅在本调用内使用，不得留存
     * @return 变更提取计划，业务方法返回后据此生成变更明细
     */
    Plan prepare(AuditOperation operation, Object[] args);

    /**
     * 启动期执行模块自定义的参数/返回值绑定校验，默认不做任何检查。
     *
     * @param method 被 {@code @Audited} 标注的入口方法
     */
    default void validate(java.lang.reflect.Method method) {
    }

    /**
     * 变更提取计划：业务方法返回后，基于预先捕获的快照生成变更明细。
     */
    @FunctionalInterface
    interface Plan {

        /**
         * 业务方法成功返回后，基于预先捕获的快照生成变更明细。
         *
         * @param result 业务方法的返回值
         * @return 已脱敏冻结的对象变更列表
         */
        List<AuditChange> after(Object result);

        /**
         * 已确认未发生写入的幂等重放可跳过成功审计。
         */
        default boolean recordSuccess(Object result) {
            return true;
        }
    }

    /**
     * 业务方法执行回调，可直接绑定 AspectJ 的 ProceedingJoinPoint.proceed()。
     */
    @FunctionalInterface
    interface Work {

        // 与 AspectJ 回调契约一致，保留业务抛出的原始 Throwable，包括 Error。
        @SuppressWarnings("java:S112")
        Object run() throws Throwable;
    }
}
