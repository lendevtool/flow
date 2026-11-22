package me.lendev.flow.workbench.todo;

import me.lendev.flow.common.exception.BadRequestException;

/**
 * 负责人不存在或已停用。
 */
public class TodoAssigneeException extends BadRequestException {

    public TodoAssigneeException() {
        super("负责人不存在或已停用");
    }
}
