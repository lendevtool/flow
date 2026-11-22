package me.lendev.flow.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 审计失败边界切面：位于事务切面之外（Order 50）， 保证常规失败记录发生在事务回滚与资源清理之后。
 */
@Aspect
@Component
@Order(50)
@RequiredArgsConstructor
@Slf4j
public class AuditBoundaryAspect {

    private final AuditContext context;
    private final AuditRecorder recorder;
    private final ObjectProvider<AuditExternalExecutor> external;

    /**
     * 审计调用的最外层边界：开启调用作用域并写入 ThreadLocal，驱动业务方法，
     * 捕获异常事实，并在事务结束后补记失败尝试。
     *
     * <p>external 操作交给 {@link AuditExternalExecutor} 处理；数据库操作直接放行，
     * 由内层 {@link AuditAspect} 与 {@link AuditExecutor} 完成变更采集。
     *
     * @param point   被 {@code @Audited} 标注的业务方法连接点
     * @param audited 入口方法上的审计注解
     * @return 业务方法的原始返回值
     * @throws IllegalStateException 存在嵌套审计入口时抛出
     * @throws Throwable             业务方法抛出的任何异常均原样上抛
     */
    @Around(value = "@annotation(audited)", argNames = "point,audited")
    public Object around(ProceedingJoinPoint point, Audited audited) throws Throwable {
        if (AuditInvocation.CURRENT.get() != null) {
            throw new IllegalStateException("嵌套审计入口需合并到一个聚合操作");
        }
        AuditOperation operation = audited.value();
        var state = new AuditInvocation.State(context.start(operation, point.getArgs()), operation,
                TransactionSynchronizationManager.isActualTransactionActive());
        AuditInvocation.CURRENT.set(state);
        log.info("审计调用开始，操作={} operationId={} requestId={} 模式={}", operation.code(),
                state.scope.operationId(), state.scope.requestId(), operation.external() ? "EXTERNAL" : "DATABASE");
        try {
            if (operation.external()) {
                return external.getObject().execute(state.scope, operation, point.getArgs(), point::proceed);
            }
            return point.proceed();
        } catch (Throwable error) {
            state.error = AuditError.from(error);
            log.info("审计调用发生异常，操作={} operationId={} 错误码={}", operation.code(),
                    state.scope.operationId(), state.error.code());
            throw error;
        } finally {
            AuditInvocation.CURRENT.remove();
            // 边界位于事务切面之外，独立事务此时已结束可安全补记；加入外层事务（joined）的情形由 AuditExecutor 的同步回调处理。
            if (!operation.external() && !state.joined) {
                recorder.completion(state.scope, operation, state.error, state.completion);
            }
        }
    }
}
