package me.lendev.flow.openapi;

import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.openapi.mapper.OpenApiAccessMapper;
import me.lendev.flow.openapi.mapper.OpenApiAccessMapper.Call;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 采集已识别应用的调用元数据，独立于管理事务审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OpenApiCalls {
    private static final int BATCH_SIZE = 500;
    private final OpenApiAccessMapper mapper;
    private final OpenApiProperties properties;
    private final ArrayBlockingQueue<Call> queue = new ArrayBlockingQueue<>(10_000);
    private final AtomicLong discarded = new AtomicLong();

    /**
     * 批量写入调用记录。数据库写入失败时丢弃该批记录，避免调用统计影响业务响应。
     */
    @Scheduled(fixedDelay = 1_000)
    void flush() {
        var batch = new ArrayList<Call>(BATCH_SIZE);
        queue.drainTo(batch, BATCH_SIZE);
        if (!batch.isEmpty()) {
            try {
                mapper.insertCalls(batch);
            } catch (DataAccessException exception) {
                discarded.addAndGet(batch.size());
                log.warn("开放 API 调用记录写入失败，请检查调用记录表、数据库连接和写入权限，environment={} batchSize={} firstRequestId={} lastRequestId={} errorType={}",
                        properties.environment(), batch.size(), batch.getFirst().requestId(), batch.getLast().requestId(),
                        exception.getClass().getSimpleName());
            }
        }
        long count = discarded.getAndSet(0);
        if (count > 0) {
            log.warn("开放 API 调用记录丢失，请检查批量写入失败告警和队列积压，并标记该时段统计缺口，environment={} discardedCount={} queuedCount={} remainingCapacity={}",
                    properties.environment(), count, queue.size(), queue.remainingCapacity());
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void removeExpired() {
        if (!properties.enabled()) {
            return;
        }
        var cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(properties.callRetentionDays());
        try {
            for (int batch = 0; batch < 10; batch++) {
                var ids = mapper.expiredCalls(cutoff, BATCH_SIZE);
                if (ids.isEmpty()) {
                    return;
                }
                mapper.deleteCalls(ids);
            }
        } catch (DataAccessException exception) {
            log.warn("开放 API 调用历史清理失败，下轮会继续，请检查数据库删除权限和历史记录增长，environment={} cutoffUtc={} retentionDays={} errorType={}",
                    properties.environment(), cutoff, properties.callRetentionDays(), exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        flush();
        int remaining = queue.size();
        if (remaining > 0) {
            log.warn("进程退出时仍有开放 API 调用记录未落库，请检查退出前的队列积压并标记统计缺口，environment={} discardedCount={}",
                    properties.environment(), remaining);
        }
    }

    void finish(Context context, int status) {
        if (context.clientId == null) {
            return;
        }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.started);
        var call = new Call(context.requestId, context.appId, context.clientId, context.endpointId, context.endpointCode,
                context.occurredAt, duration, status, context.errorCode, context.sourceIp);
        if (!queue.offer(call)) {
            discarded.incrementAndGet();
        }
    }

    static final class Context {
        private final String requestId;
        private final String sourceIp;
        private final LocalDateTime occurredAt = LocalDateTime.now(ZoneOffset.UTC);
        private final long started = System.nanoTime();
        private long appId;
        private String clientId;
        private Long endpointId;
        private String endpointCode;
        private String errorCode;

        Context(String requestId, String sourceIp) {
            this.requestId = requestId;
            this.sourceIp = sourceIp;
        }

        String requestId() {
            return requestId;
        }

        Long appId() {
            return clientId == null ? null : appId;
        }

        String endpointCode() {
            return endpointCode;
        }

        void application(long appId, String clientId) {
            this.appId = appId;
            this.clientId = clientId;
        }

        void endpoint(Long endpointId, String endpointCode) {
            this.endpointId = endpointId;
            this.endpointCode = endpointCode;
        }

        void failed(String errorCode) {
            this.errorCode = errorCode;
        }
    }
}
