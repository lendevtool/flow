package me.lendev.flow.workbench.todo;

/**
 * 记录不存在或当前用户不可见。
 */
public class TodoNotFoundException extends RuntimeException {

    public TodoNotFoundException() {
        super("error.todo.not-found");
    }
}
