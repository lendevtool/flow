package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.workbench.flow.FlowModels.DefinitionStatus;

/**
 * 流程定义的持久化实体，对应 wf_definition 表，保存流程编码、修订版本、步骤与字段配置及发布状态。
 */
@Getter
@Setter
@Table(value = "wf_definition", camelToUnderline = false)
public class FlowDefinition {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String code;
    /**
     * 同一编码下的修订号，发布新版本时递增。
     */
    private Integer revision;
    private String name;
    private DefinitionStatus status;
    /**
     * 步骤配置 JSON。
     */
    private String stepsJson;
    /**
     * 步骤与字段配置的结构版本号。
     */
    private Integer schemaVersion;
    /**
     * 表单字段配置 JSON。
     */
    private String fieldsJson;
    private Integer createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    /**
     * 乐观锁版本。
     */
    private Long version;
}
