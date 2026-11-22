package me.lendev.flow.audit;

import java.util.List;

import me.lendev.flow.audit.model.AuditChange;

/**
 * 外部操作可选实现的安全结果元数据接口；业务返回值绝不会被反射序列化。
 */
public interface AuditExternalMetadata {

    /**
     * @return 随外部操作结果一并记录的对象变更列表，仅含已脱敏的安全数据
     */
    List<AuditChange> auditChanges();

    /**
     * @return 面向审计展示的中文摘要，覆盖操作定义的默认标签
     */
    String auditSummary();
}
