package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色办理人配置的持久化实体，对应 wf_role_handler 表，以 userId+roleId 复合主键记录办理人类别（主办 PRIMARY / 备办 BACKUP）。
 */
@Getter
@Setter
@Table(value = "wf_role_handler", camelToUnderline = false)
public class WorkflowHandler {

    @Id
    private Integer userId;
    @Id
    private Integer roleId;
    /**
     * 办理人类别：PRIMARY 主办 / BACKUP 备办。
     */
    private String kind;
    /**
     * 主办时记录角色 ID，配合唯一约束保证每角色单一主办；备办为 null。
     */
    private Integer primaryRoleId;
}
