package me.lendev.flow.audit;

import java.util.ArrayList;

import me.lendev.flow.audit.model.AuditChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 数据库审计执行器：在可写事务内驱动业务方法，注册事务同步以捕获提交/回滚结果， 并在成功返回后记录变更事实。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditExecutor {

    private final AuditRegistry registry;
    private final AuditRecorder recorder;

    /**
     * 在可写事务内驱动一次数据库审计调用。
     *
     * <p>先注册事务同步以捕获提交/回滚结果，再执行业务方法并记录成功事件；
     * 加入外层事务（joined）时完成状态由同步回调补记。
     *
     * @param state 边界切面预先写入的调用级审计状态
     * @param args  入口方法的原始参数，仅用于准备阶段提取快照
     * @param work  业务方法执行回调
     * @return 业务方法的原始返回值
     * @throws IllegalStateException 当前不处于可写事务时抛出
     * @throws Throwable             业务方法抛出的任何异常均原样上抛
     */
    Object execute(AuditInvocation.State state, Object[] args, AuditHandler.Work work) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("数据库审计必须处于可写事务内");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                state.completion = status;
                // 加入外层事务时，外层事务可能在被注解方法返回之后才结束，须由该同步回调补记完成状态。
                if (state.joined) {
                    recorder.completion(state.scope, state.operation, state.error, status);
                }
            }
        });
        AuditHandler.Plan plan = registry.handler(state.operation).prepare(state.operation, args);
        Object result = work.run();
        boolean recordPrimary = plan.recordSuccess(result);
        if (recordPrimary || !state.related.isEmpty()) {
            var changes = new ArrayList<AuditChange>();
            if (recordPrimary) {
                changes.addAll(plan.after(result));
            }
            changes.addAll(state.related);
            recorder.success(state.scope, state.operation, changes);
        } else {
            log.info("幂等重放未发生写入，跳过成功审计，操作={} operationId={}", state.operation.code(),
                    state.scope.operationId());
        }
        return result;
    }
}
