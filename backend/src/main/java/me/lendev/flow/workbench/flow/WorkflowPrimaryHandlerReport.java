package me.lendev.flow.workbench.flow;

import java.util.List;

/**
 * 模板中需要主办但无法分派的节点清单。
 */
public record WorkflowPrimaryHandlerReport(boolean valid, List<Issue> missingPrimaryHandlers) {

    WorkflowPrimaryHandlerReport(List<Issue> issues) {
        this(issues.isEmpty(), List.copyOf(issues));
    }

    /**
     * 按模板节点返回角色和阻塞原因，未选角色时角色编码为空。
     */
    public record Issue(String nodeKey, String nodeTitle, String roleCode, String roleName, String roleNameEn,
                        Reason reason) {
    }

    /**
     * 阻塞原因：未选角色、角色不存在、角色停用、无可用主办。
     */
    public enum Reason {
        ROLE_REQUIRED, ROLE_NOT_FOUND, ROLE_DISABLED, PRIMARY_UNAVAILABLE
    }
}
