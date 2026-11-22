package me.lendev.flow.audit.store;

/**
 * 审计载荷校验异常：绝不携带被拒绝的载荷内容或原始异常信息，防止敏感数据经异常泄露。
 */
public class AuditPayloadException extends RuntimeException {

    public AuditPayloadException() {
        super("审计载荷无效或与已冻结事实不一致");
    }
}
