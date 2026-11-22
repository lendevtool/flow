package me.lendev.flow.audit;

/**
 * 审计基础设施不可用异常：抛出后业务事务回滚，保证"审计不可写则操作不提交"。
 */
public class AuditUnavailableException extends RuntimeException {

    /**
     * @param cause 审计持久化失败的底层异常，仅供服务端诊断，不向调用方暴露细节
     */
    public AuditUnavailableException(Throwable cause) {
        super("审计记录暂不可用，本次操作未提交，请稍后重试", cause);
    }
}
