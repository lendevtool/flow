package me.lendev.flow.audit.query;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 审计队列人工重试请求体：携带乐观锁版本号，防止基于过期状态重试。
 */
@Data
public class AuditRetryRequest {

    /**
     * 调用方持有的 outbox 行版本号，用于乐观并发控制，防止基于过期状态重试。
     */
    @NotNull
    @PositiveOrZero
    private Long rowVersion;
}
