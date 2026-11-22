package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 节点执行实例的持久化实体，对应 wf_node_instance 表，记录节点的阶段、尝试次数、办理角色与执行状态。
 */
@Getter
@Setter
@Table(value = "wf_node_instance", camelToUnderline = false)
public class WorkflowAttempt {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long flowInstanceId;
    private String nodeKey;
    /**
     * 复用来源的父节点实例 ID，非复用节点为 null。
     */
    private Long parentNodeInstanceId;
    /**
     * 节点内阶段号。
     */
    private Integer stageNo;
    /**
     * 同一节点的尝试序号，驳回重办时递增。
     */
    private Integer attemptNo;
    private Integer roleId;
    private String roleName;
    /**
     * 办理模式（如单人、会签等）。
     */
    private String mode;
    /**
     * 节点执行状态。
     */
    private String status;
    /**
     * 本轮分派原因，首次办理和收回为 NORMAL，驳回重开为 RETURNED。
     */
    private String assignmentReason;
    private LocalDateTime activatedAt;
    private LocalDateTime closedAt;
}
