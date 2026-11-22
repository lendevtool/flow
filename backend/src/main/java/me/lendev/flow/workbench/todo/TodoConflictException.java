package me.lendev.flow.workbench.todo;

/**
 * 待办、规则或流程的并发版本、预览或幂等键冲突。
 * 调用方应提示用户刷新后重试，message 为稳定的错误码键。
 */
public class TodoConflictException extends RuntimeException {

    /**
     * @param message 标识版本冲突等具体原因的错误码键
     */
    public TodoConflictException(String message) {
        super(message);
    }
}
