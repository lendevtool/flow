package me.lendev.flow.audit;

/**
 * 审计入口返回值与处理器契约不符。业务事务必须回滚，详细类型仅供服务端诊断。
 */
public final class AuditResultMismatchException extends IllegalStateException {

    private final String requestId;

    /**
     * @param operation 审计操作定义
     * @param expected  处理器契约预期的返回值类型描述
     * @param actual    业务方法实际返回值类型，{@code null} 表示返回 null
     */
    public AuditResultMismatchException(AuditOperation operation, String expected, Class<?> actual) {
        super("审计结果类型不匹配: 操作=" + operation.code() + ", 预期=" + expected
                + ", 实际=" + (actual == null ? "null" : actual.getName()));
        this.requestId = AuditParticipants.currentRequestId();
    }

    /**
     * @return 异常发生时所在审计调用的 HTTP 请求 ID，后台调用为 {@code null}
     */
    public String requestId() {
        return requestId;
    }
}
