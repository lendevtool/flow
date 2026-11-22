package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 流程事件的持久化实体，对应 wf_flow_event 表，记录节点流转、任务处理等事件及用于幂等的请求标识。
 */
@Getter
@Setter
@Table(value = "wf_flow_event", camelToUnderline = false)
public class WorkflowEvent {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long flowInstanceId;
    private Long fromNodeInstanceId;
    private Long toNodeInstanceId;
    private Long taskId;
    private String eventType;
    private Integer actorUserId;
    private String reason;
    /**
     * 幂等请求键，重复提交时回放原事件。
     */
    private String requestKey;
    /**
     * 请求内容摘要，幂等回放时比对。
     */
    private String requestHash;
    private LocalDateTime createdAt;
}
