package me.lendev.flow.workbench.todo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 用户个人维度配置的持久化实体。
 */
@Getter
@Setter
@Table(value = "wb_todo_personal_dimension", camelToUnderline = false)
public class TodoPersonalDimension {

    @Id
    private Integer userId;
    @Id
    private Integer slotNo;
    private String name;
    /**
     * 选项列表 JSON，空数组表示文本维度。
     */
    private String optionsJson;
    private Boolean enable;
    /**
     * 乐观锁版本，新建为 0，每次配置变更递增。
     */
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
