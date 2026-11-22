package me.lendev.flow.workbench.flow;

import lombok.Getter;
import me.lendev.flow.common.exception.BadRequestException;

/**
 * 当前角色主办配置无法支持流程操作，拒绝写入并携带所有阻塞节点。
 */
@Getter
public final class WorkflowPrimaryHandlerException extends BadRequestException {

    private final transient WorkflowPrimaryHandlerReport report;

    WorkflowPrimaryHandlerException(WorkflowPrimaryHandlerReport report) {
        super("error.workflow.primary-handler.invalid");
        this.report = report;
    }
}
