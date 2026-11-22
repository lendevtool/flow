package me.lendev.flow.audit.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.ToString;

/**
 * 审计事件的单条对象明细，对应表 {@code sys_operation_change}。
 *
 * <p>通过 logId 关联 {@link OperationLog}，objectNo 保持对象在事件内的顺序。
 * beforeData/afterData/changes 为冻结的 JSON 快照，不参与 toString，防止敏感数据进入日志。
 */
@Data
@Table(value = "sys_operation_change", camelToUnderline = false)
public class OperationChange {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long logId;
    private Integer objectNo;
    private String entryType;
    private String objectType;
    private String objectId;
    private String objectCode;
    private String objectName;
    private String parentType;
    private String parentId;
    private LocalDateTime occurredAt;
    private Integer snapshotVersion;
    private String snapshotScope;
    @ToString.Exclude
    private String beforeData;
    @ToString.Exclude
    private String afterData;
    @ToString.Exclude
    private String changes;
    private Integer fieldChangeCount;
}
