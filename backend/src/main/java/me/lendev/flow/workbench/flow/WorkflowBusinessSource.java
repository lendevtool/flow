package me.lendev.flow.workbench.flow;

import java.util.List;
import java.util.Map;
import java.util.Set;

import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.EventType;
import me.lendev.flow.workbench.todo.TodoBusinessSource;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import tools.jackson.databind.JsonNode;

/**
 * 业务模块的角色流程适配器。每个模块编码只注册一个 Spring Bean。
 */
public interface WorkflowBusinessSource extends TodoBusinessSource {

    /**
     * 检查本次发起人、业务单状态和允许使用的模板。首次发起时调用。
     */
    boolean mayStart(Source source, String templateCode);

    /**
     * 检查指定用户是否可以查询此业务单关联的流程摘要。
     */
    boolean mayRead(Source source, int viewer);

    /**
     * 检查来源服务是否有权取消此单据对应的流程。
     */
    default boolean mayCancel(Source source, int actor) {
        return false;
    }

    /**
     * 允许业务初始化的字段编码，默认不接收业务字段。
     */
    default Set<String> inputFields(Source source, String templateCode) {
        return Set.of();
    }

    /**
     * 只能从业务单据发起的模板编码。
     */
    default Set<String> businessOnlyTemplates() {
        return Set.of();
    }

    /**
     * 校验并规范化节点提交的业务字段。调用方已校验可编辑范围。
     */
    default Map<String, JsonNode> normalizeFields(Source source, Map<String, JsonNode> values) {
        return values;
    }

    /**
     * 将节点可编辑字段缩小到本次业务申请的范围。默认沿用模板权限。
     */
    default List<String> editableFields(Source source, List<String> templateFields) {
        return templateFields;
    }

    /**
     * 允许投递给业务模块的字段编码，默认仅投递事件元数据。
     */
    default Set<String> outputFields(Source source, String templateCode, EventType type) {
        return Set.of();
    }

    /**
     * 默认订阅完成和取消。也可订阅驳回、收回和待分派。
     */
    default Set<EventType> subscriptions() {
        return Set.of(EventType.COMPLETED, EventType.CANCELLED);
    }

    /**
     * 处理已提交的流程事件。失败时抛出异常，由队列重试。 使用 eventId 去重，在同一个业务事务中保存去重记录和业务状态。
     *
     * @param event 待投递的流程事件
     * @throws Exception 业务适配器可抛出其依赖声明的受检异常，投递队列统一处理重试
     */
    @SuppressWarnings("java:S112")
    void onEvent(Event event) throws Exception;
}
