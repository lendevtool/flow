package me.lendev.flow.audit.model;

import java.util.Objects;

/**
 * 审计对象的历史标识快照：不依赖业务表外键的存活状态。
 */
public record AuditObject(String objectType, String objectId, String objectCode,
                          String objectName, String parentType, String parentId) {

    public AuditObject {
        Objects.requireNonNull(objectType);
        Objects.requireNonNull(objectId);
        Objects.requireNonNull(objectName);
        if ((parentType == null) != (parentId == null)) {
            throw new IllegalArgumentException("审计父对象类型与标识必须同时填写");
        }
    }

    /**
     * 判断两个对象快照是否指向同一历史标识（类型、标识与父对象一致），用于更新前后快照的一致性校验。
     *
     * @param other 待比较的对象快照
     * @return 标识一致返回 true
     */
    public boolean sameIdentity(AuditObject other) {
        return objectType.equals(other.objectType) && objectId.equals(other.objectId)
                && Objects.equals(parentType, other.parentType) && Objects.equals(parentId, other.parentId);
    }
}
