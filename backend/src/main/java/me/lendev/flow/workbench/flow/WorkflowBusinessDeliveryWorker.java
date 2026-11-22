package me.lendev.flow.workbench.flow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 在流程事务之外交付业务结果。模块按事件 ID 处理重复消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "workflow.business.delivery.enabled", matchIfMissing = true)
class WorkflowBusinessDeliveryWorker {

    private final WorkflowBusinessDelivery queue;
    private final WorkflowBusinessRegistry modules;
    private final ObjectMapper json;

    /**
     * 定时拉取候选并逐条投递，单条失败不影响其余事件。
     */
    @Scheduled(fixedDelayString = "${workflow.business.delivery.poll-ms:5000}",
            initialDelayString = "${workflow.business.delivery.poll-ms:5000}", scheduler = "workflowBusinessScheduler")
    public void poll() {
        for (long id : queue.eligible()) {
            try {
                deliver(id);
            } catch (RuntimeException error) {
                log.warn("流程业务投递领取或回执写入失败，请检查投递队列表和租约状态，回执缺失时按事件 ID 核对业务结果，eventId={} errorType={}",
                        id, error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 领取租约后调用业务适配器，成功与否都确认回执；
     * 适配器须按 eventId 去重，失败由队列按退避策略重试。
     */
    private void deliver(long id) {
        var claim = queue.claim(id);
        if (claim == null) {
            return;
        }
        String errorCode = null;
        try (var ignored = AuditSystemContext.open("流程业务结果投递")) {
            var event = json.readValue(claim.payload(), Event.class);
            modules.require(event.source().module()).onEvent(event);
            log.info("流程业务投递完成，eventId={}，模块={}，type={}", id, event.source().module(), event.eventType());
        } catch (Exception error) {
            errorCode = "DELIVERY_FAILED";
            log.warn("流程业务回调执行失败，将记录失败回执，请按事件 ID 检查载荷、对应模块适配器及业务落库结果，eventId={} errorCode={} errorType={}",
                    id, errorCode, error.getClass().getSimpleName());
        }
        queue.acknowledge(claim, errorCode == null, errorCode);
    }
}
