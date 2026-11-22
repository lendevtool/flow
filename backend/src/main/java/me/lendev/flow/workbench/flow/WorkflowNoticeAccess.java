package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.stereotype.Service;

/**
 * 为站内消息计算当前登录用户可用的流程或待办跳转。
 */
@Service
@RequiredArgsConstructor
public class WorkflowNoticeAccess {

    private final WorkflowRuntimeRepository workflows;
    private final TodoService todos;

    /**
     * 按当前登录人和目标的原有权限规则生成跳转。节点已关闭时仍可查看历史。
     *
     * @param userId     当前收件人的登录用户 ID
     * @param targetType 业务保存的跳转目标类型
     * @param targetId   对应类型的业务主键
     * @return 可访问的工作台相对路径，不可访问或不存在时返回空
     */
    public String targetUrl(int userId, String targetType, long targetId) {
        if (targetType == null || targetId <= 0 || !currentViewer(userId)) {
            return null;
        }
        return switch (targetType) {
            case "WORKFLOW_TASK" -> taskUrl(userId, targetId);
            case "WORKFLOW_INSTANCE" -> workflowUrl(userId, targetId);
            case "WORKBENCH_TODO" -> todoUrl(userId, targetId);
            default -> null;
        };
    }

    private static boolean currentViewer(int userId) {
        try {
            return Integer.toString(userId).equals(String.valueOf(StpUtil.getLoginIdDefaultNull()));
        } catch (SaTokenContextException noRequest) {
            return false;
        }
    }

    private String workflowUrl(int userId, long instanceId) {
        return workflows.visible(instanceId, userId, WorkflowRuns.manager())
                ? "/workbench/workflow-task/" + instanceId : null;
    }

    private String taskUrl(int userId, long taskId) {
        return workflows.task(taskId).map(task -> {
            var path = workflowUrl(userId, workflows.flowId(task));
            return path == null ? null : path + "?mode=task&todo=" + task.getTodoId();
        }).orElse(null);
    }

    private String todoUrl(int userId, long todoId) {
        if (!TodoAccess.can(WORKBENCH_TODO)) {
            return null;
        }
        try {
            todos.detail(todoId, userId);
            return "/workbench/todos?todo=" + todoId;
        } catch (TodoNotFoundException unavailable) {
            return null;
        }
    }
}
