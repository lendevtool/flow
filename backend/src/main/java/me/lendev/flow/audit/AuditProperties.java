package me.lendev.flow.audit;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 审计模块配置属性：绑定 {@code audit.*} 配置键，控制消费者开关、轮询间隔、批量大小、 租约与准备超时、失败上限及载荷大小限制。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    /**
     * 消费者开关，对应 {@code audit.worker-enabled}，关闭后定时轮询直接返回。
     */
    private boolean workerEnabled = true;
    /**
     * 轮询间隔毫秒，对应 {@code audit.poll-interval-ms}，最小 100。
     */
    @Min(100)
    private long pollIntervalMs = 1000;
    /**
     * 单轮领取与恢复的批量上限，对应 {@code audit.batch-size}，范围 1 至 1000。
     */
    @Min(1)
    @Max(1000)
    private int batchSize = 100;
    /**
     * 投递租约有效秒数，对应 {@code audit.lease-seconds}，过期后由恢复流程重新投递。
     */
    @Min(5)
    private int leaseSeconds = 60;
    /**
     * 外部意图准备超时秒数，对应 {@code audit.prepare-timeout-seconds}，超时后发布 UNKNOWN 结果。
     */
    @Min(5)
    private int prepareTimeoutSeconds = 120;
    /**
     * 本轮失败次数上限，对应 {@code audit.max-failures}，达到后置为 DEAD。
     */
    @Min(1)
    private int maxFailures = 20;
    /**
     * 单事件载荷字节上限，对应 {@code audit.max-payload-bytes}，范围 1KB 至 64MB。
     */
    @Min(1024)
    @Max(67108864)
    private int maxPayloadBytes = 8 * 1024 * 1024;
}
