package me.lendev.flow.workbench.todo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 待办所属业务单据及任务环节的持久化实体。
 */
@Getter
@Setter
@Table(value = "wb_todo_business_context", camelToUnderline = false)
public class TodoBusinessContext {

    @Id
    private Long todoId;
    private String recordModule;
    private String recordType;
    private String recordId;
    private String taskKindCode;
    private LocalDateTime createdAt;
}
