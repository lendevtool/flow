package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 附件上传绑定的持久化实体，对应 wf_attachment_upload 表，记录流程实例、节点任务与文档版本的关联关系。
 */
@Getter
@Setter
@Table(value = "wf_attachment_upload", camelToUnderline = false)
public class WorkflowUpload {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long flowInstanceId;
    /**
     * 绑定的节点任务 ID，未绑定为 null。
     */
    private Long nodeTaskId;
    private String fieldCode;
    private Integer uploaderUserId;
    private Long documentId;
    private Long versionId;
    /**
     * 绑定到节点任务的时间，未绑定为 null。
     */
    private LocalDateTime boundAt;
    private LocalDateTime createdAt;
}
