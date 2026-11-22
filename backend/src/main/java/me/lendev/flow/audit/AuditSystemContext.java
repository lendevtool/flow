package me.lendev.flow.audit;

import me.lendev.flow.audit.model.AuditActor;

/**
 * 定时任务与非 HTTP 调用方的显式服务端身份；绝不从请求输入填充，防止身份伪造。
 */
public final class AuditSystemContext {

    private static final ThreadLocal<AuditActor> CURRENT = new ThreadLocal<>();

    private AuditSystemContext() {
    }

    /**
     * 开启系统身份作用域，作用域内的审计操作以该系统名称作为操作者。
     *
     * @param name 系统任务名称，长度 1 至 100 字
     * @return 可关闭的作用域句柄，关闭后恢复此前的身份上下文
     */
    public static Scope open(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("系统任务名称应为1至100字");
        }
        var previous = CURRENT.get();
        CURRENT.set(AuditActor.system(RequestAuditContext.clean(name, 100)));
        return new Scope(previous);
    }

    /**
     * @return 当前线程的系统身份；未开启系统作用域时返回 {@code null}
     */
    static AuditActor actor() {
        return CURRENT.get();
    }

    /**
     * 系统身份作用域句柄：支持嵌套开启，关闭操作幂等。
     */
    public static final class Scope implements AutoCloseable {

        private final AuditActor previous;
        private boolean closed;

        private Scope(AuditActor previous) {
            this.previous = previous;
        }

        /**
         * 关闭作用域并恢复此前的身份上下文；重复关闭幂等，不会重复恢复。
         */
        @Override
        public void close() {
            if (!closed) {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
                closed = true;
            }
        }
    }
}
