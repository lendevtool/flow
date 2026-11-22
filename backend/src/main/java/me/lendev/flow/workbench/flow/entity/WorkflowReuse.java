package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 节点复用关系的持久化实体，对应 wf_node_reuse 表，以父、子节点实例 ID 复合主键记录复用链接。
 */
@Getter
@Setter
@Table(value = "wf_node_reuse", camelToUnderline = false)
public class WorkflowReuse {

    @Id
    private Long parentNodeInstanceId;
    @Id
    private Long childNodeInstanceId;
}
