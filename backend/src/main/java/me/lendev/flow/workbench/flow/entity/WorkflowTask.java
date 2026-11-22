package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 节点任务的持久化实体，对应 wf_node_task 表，记录任务办理人、委托来源、处理结果、意见与附件快照。
 */
@Getter
@Setter
@Table(value = "wf_node_task", camelToUnderline = false)
public class WorkflowTask {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long nodeInstanceId;
    private Long todoId;
    private Integer assigneeUserId;
    /**
     * 领取身份：PRIMARY/DELEGATE/BROADCAST/COUNTERSIGN。
     */
    private String identity;
    /**
     * 委托任务对应的委托关系 ID。
     */
    private Long delegationId;
    /**
     * 委托来源的原任务 ID。
     */
    private Long delegatedFromTaskId;
    private Integer processedBy;
    private String result;
    private String opinion;
    private LocalDateTime processedAt;
    /**
     * 处理时附件快照 JSON，用于审计留痕。
     */
    private String attachmentSnapshotJson;
}
