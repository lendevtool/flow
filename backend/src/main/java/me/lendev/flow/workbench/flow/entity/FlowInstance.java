package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;

/**
 * 流程运行状态的持久化实体，对应 wf_instance 表，记录单次运行的步骤状态、字段取值、维度归属及乐观锁版本。
 */
@Getter
@Setter
@Table(value = "wf_instance", camelToUnderline = false)
public class FlowInstance {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long definitionId;
    private String title;
    private Integer ownerUserId;
    private String ownerName;
    private String sourceModule;
    private String businessType;
    private String businessId;
    /**
     * 幂等作用域，与 requestKey 构成唯一约束。
     */
    private String requestScope;
    private String requestKey;
    /**
     * 发起请求内容摘要，幂等回放时比对。
     */
    private String requestHash;
    /**
     * 各步骤运行状态 JSON。
     */
    private String stepsStateJson;
    /**
     * 表单字段取值 JSON。
     */
    private String fieldValuesJson;
    /**
     * 当前所处步骤号。
     */
    private Integer currentStepNo;
    private RunStatus status;
    /**
     * 公共维度槽位 1-5，指向参数项 ID。
     */
    private Long dimension1ItemId;
    private Long dimension2ItemId;
    private Long dimension3ItemId;
    private Long dimension4ItemId;
    private Long dimension5ItemId;
    /**
     * 乐观锁版本，每次运行状态写入递增。
     */
    private Long version;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;
    private LocalDateTime updatedAt;
}
