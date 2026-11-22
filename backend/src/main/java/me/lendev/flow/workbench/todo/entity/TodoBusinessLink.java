package me.lendev.flow.workbench.todo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 待办附加归集对象的持久化实体。
 */
@Getter
@Setter
@Table(value = "wb_todo_business_link", camelToUnderline = false)
public class TodoBusinessLink {

    @Id
    private Long todoId;
    @Id
    private String refModule;
    @Id
    private String refType;
    @Id
    private String refId;
    private LocalDateTime createdAt;
}
