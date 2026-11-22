package me.lendev.flow.workbench.todo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 待办个人维度标记的持久化实体。
 */
@Getter
@Setter
@Table(value = "wb_todo_personal_value", camelToUnderline = false)
public class TodoPersonalValue {

    @Id
    private Integer userId;
    @Id
    private Long todoId;
    @Id
    private Integer slotNo;
    /**
     * 选项键或文本值，文本维度直接存用户输入。
     */
    private String optionKey;
    private LocalDateTime updatedAt;
}
