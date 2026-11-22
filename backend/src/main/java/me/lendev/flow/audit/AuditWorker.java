package me.lendev.flow.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.store.AuditPayloadException;
import me.lendev.flow.audit.store.AuditStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计队列消费者：按固定间隔轮询 outbox，先恢复过期租约与超时准备事件，再逐条领取并投递。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditWorker {

    private final AuditStore store;
    private final AuditProperties properties;

    /**
     * 定时轮询入口：消费者开关关闭时直接返回；单轮异常仅告警，等待下次轮询恢复。
     */
    @Scheduled(fixedDelayString = "${audit.poll-interval-ms:1000}")
    public void poll() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException exception) {
            log.warn("审计轮询中断，下轮继续扫描，请检查审计数据库连接及 /audit/outbox 积压，batchSize={} errorType={}",
                    properties.getBatchSize(), exception.getClass().getSimpleName());
        }
    }

    /**
     * 执行一轮投递：先恢复过期租约与超时准备事件，再逐条领取并投递到期记录。
     *
     * <p>载荷校验失败记为永久失败（DEAD）；其余投递失败持久化重试并按退避策略延后。
     */
    public void runOnce() {
        store.recoverExpired();
        store.recoverPrepared();
        for (AuditStore.Delivery candidate : store.due()) {
            AuditStore.Lease lease = store.claim(candidate);
            if (lease == null) {
                continue;
            }
            try {
                store.consume(lease);
                log.info("审计事件投递成功，事件队列ID={} eventId={} operationId={}", lease.id(), candidate.eventId(),
                        candidate.operationId());
            } catch (AuditPayloadException exception) {
                store.failed(lease, "PAYLOAD_INVALID", true);
                log.warn("审计载荷校验失败，已按永久失败处理，请核对审计事件格式和部署版本，修复后再重试，outboxId={} eventId={} operationId={} errorCode=PAYLOAD_INVALID",
                        lease.id(), candidate.eventId(), candidate.operationId());
            } catch (RuntimeException exception) {
                store.failed(lease, "DELIVERY_FAILED", false);
                log.warn("审计投递失败，请检查审计库写入和 /audit/outbox 状态，RETRY 等待退避，DEAD 排障后人工重试，outboxId={} eventId={} operationId={} failureCount={} maxFailures={} errorCode=DELIVERY_FAILED errorType={}",
                        lease.id(), candidate.eventId(), candidate.operationId(), candidate.failureCount() + 1,
                        properties.getMaxFailures(), exception.getClass().getSimpleName());
            }
        }
    }
}
