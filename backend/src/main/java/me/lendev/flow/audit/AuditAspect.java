package me.lendev.flow.audit;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 数据库审计执行切面：顺序（Order 200）位于事务切面之内， 在可写事务中驱动业务方法并采集变更事实。
 */
@Aspect
@Component
@Order(200)
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditExecutor executor;

    /**
     * 在可写事务内驱动数据库审计调用；external 操作直接放行给外部执行器。
     *
     * @param point   被 {@code @Audited} 标注的业务方法连接点
     * @param audited 入口方法上的审计注解
     * @return 业务方法的原始返回值
     * @throws IllegalStateException 审计调用边界缺失（代理顺序被破坏）时抛出
     * @throws Throwable             业务方法抛出的任何异常均原样上抛
     */
    @Around(value = "@annotation(audited)", argNames = "point,audited")
    public Object around(ProceedingJoinPoint point, Audited audited) throws Throwable {
        // external 操作由 AuditBoundaryAspect 交给外部执行器处理，这里直接放行。
        if (audited.value().external()) {
            return point.proceed();
        }
        AuditInvocation.State state = AuditInvocation.CURRENT.get();
        // 边界状态由 AuditBoundaryAspect 预先写入 ThreadLocal；缺失说明代理顺序被破坏。
        if (state == null) {
            throw new IllegalStateException("审计调用边界缺失");
        }
        return executor.execute(state, point.getArgs(), point::proceed);
    }
}
