package me.lendev.flow.audit;

import java.util.ArrayList;
import java.util.List;

import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditScope;

/**
 * 调用级审计控制状态。参与方仅提交已脱敏冻结的变更，调用结束后释放。
 */
final class AuditInvocation {

    /**
     * 当前线程的审计调用状态，由边界切面写入并在 finally 中移除，绝不在调用间复用。
     */
    static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    /**
     * 单次审计调用的可变状态：作用域与操作定义在边界开启时冻结，
     * related 收集同事务参与者的变更，error/completion 记录异常事实与事务完成状态。
     */
    static final class State {

        /**
         * 冻结的调用作用域。
         */
        final AuditScope scope;
        /**
         * 本次审计操作定义。
         */
        final AuditOperation operation;
        /**
         * 边界开启时是否已存在外层事务；joined 时完成状态由事务同步回调补记。
         */
        final boolean joined;
        /**
         * 同事务参与者附加的对象变更。
         */
        final List<AuditChange> related = new ArrayList<>();
        /**
         * 业务异常归类后的错误事实，无异常时为 {@code null}。
         */
        AuditError error;
        /**
         * 事务完成状态，未参与事务同步时为 {@code null}。
         */
        Integer completion;

        State(AuditScope scope, AuditOperation operation, boolean joined) {
            this.scope = scope;
            this.operation = operation;
            this.joined = joined;
        }
    }

    private AuditInvocation() {
    }
}
