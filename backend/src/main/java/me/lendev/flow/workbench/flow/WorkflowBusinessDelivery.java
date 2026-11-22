package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.entity.table.WorkflowDeliveryTableDef.WORKFLOW_DELIVERY;

import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.EventType;
import me.lendev.flow.workbench.flow.entity.WorkflowDelivery;
import me.lendev.flow.workbench.flow.entity.WorkflowEvent;
import me.lendev.flow.workbench.flow.mapper.WorkflowDeliveryMapper;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 与流程同事务保存载荷，使用短事务领取及确认投递。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowBusinessDelivery {

    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String DELIVERED = "DELIVERED";
    private static final String FAILED = "FAILED";
    static final int MAX_ATTEMPTS = 8;
    static final int LEASE_SECONDS = 120;

    private final WorkflowDeliveryMapper mapper;
    private final WorkflowBusinessRegistry modules;
    private final WorkflowRuntimeRepository runtime;
    private final WorkflowPeople clock;
    private final ObjectMapper json;

    /**
     * 领取到的投递：事件 ID、租约令牌与载荷 JSON，确认时须回传令牌。
     */
    record Claim(long eventId, String token, String payload) {

    }

    /**
     * 投递状态视图，供管理端查询与重试。
     */
    record DeliveryInfo(long eventId, String status, int attemptCount, LocalDateTime nextAttemptAt,
                        LocalDateTime deliveredAt, String lastError) {

    }

    /**
     * 与流程事件同事务入队：仅业务来源实例且模块订阅的事件类型才投递，
     * 载荷按模块声明的输出字段裁剪。
     */
    void enqueue(WorkflowRun flow, WorkflowEvent event) {
        var source = flow.source();
        if (source == null) {
            return;
        }
        var type = switch (event.getEventType()) {
            case "COMPLETED" -> EventType.COMPLETED;
            case "CANCELLED" -> EventType.CANCELLED;
            case "RETURNED" -> EventType.RETURNED;
            case "RECALLED" -> EventType.RECALLED;
            case "WAITING_ASSIGNMENT" -> EventType.WAITING_ASSIGNMENT;
            default -> null;
        };
        if (type == null) {
            return;
        }
        var module = modules.require(source.module());
        if (!module.subscriptions().contains(type)) {
            return;
        }
        var row = flow.row();
        var values = output(flow, module, type);
        var payload = new Event(Long.toString(event.getId()), 1, type, source, Long.toString(row.getId()),
                Long.toString(row.getDefinitionId()), flow.definition().code(), flow.definition().revision(),
                row.getVersion(), row.getStatus(), event.getCreatedAt(),
                event.getActorUserId() == 0 ? null : event.getActorUserId(),
                nodeKey(flow, event.getFromNodeInstanceId()), nodeKey(flow, event.getToNodeInstanceId()), values);
        var delivery = new WorkflowDelivery();
        delivery.setEventId(event.getId());
        delivery.setStatus(PENDING);
        delivery.setAttemptCount(0);
        delivery.setPayloadJson(json.writeValueAsString(payload));
        delivery.setNextAttemptAt(event.getCreatedAt());
        delivery.setCreatedAt(event.getCreatedAt());
        delivery.setUpdatedAt(event.getCreatedAt());
        mapper.insertSelective(delivery);
        log.info("流程业务投递入队，eventId={}，type={}，模块={}", event.getId(), type, source.module());
    }

    private static String nodeKey(WorkflowRun flow, Long id) {
        return id == null ? null : flow.attempt(id).getNodeKey();
    }

    private Map<String, JsonNode> output(WorkflowRun flow, WorkflowBusinessSource module, EventType type) {
        var fields = module.outputFields(flow.source(), flow.definition().code(), type);
        var result = new LinkedHashMap<String, JsonNode>();
        runtime.values(flow.row().getFieldValuesJson()).forEach((key, value) -> {
            if (fields.contains(key)) {
                result.put(key, value);
            }
        });
        return result;
    }

    /**
     * 查询可投递候选，实例内按事件 ID 顺序投递。
     */
    List<Long> eligible() {
        return mapper.eligible(clock.now(), 50);
    }

    /**
     * 每次只锁定一个投递行，重新检查领取时间，过期租约可恢复。
     * 独立短事务提交，尝试次数耗尽时直接标记失败。
     *
     * @return 领取成功返回租约与载荷，未到时间或已耗尽返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Claim claim(long id) {
        var row = lock(id);
        var now = clock.now();
        if (!due(row, now)) {
            return null;
        }
        if (row.getAttemptCount() >= MAX_ATTEMPTS) {
            finish(row, false, "LEASE_EXPIRED", now);
            return null;
        }
        row.setStatus(PROCESSING);
        row.setAttemptCount(row.getAttemptCount() + 1);
        row.setNextAttemptAt(null);
        row.setLeaseToken(UUID.randomUUID().toString());
        row.setLeaseExpiresAt(now.plusSeconds(LEASE_SECONDS));
        row.setUpdatedAt(now);
        mapper.update(row, false);
        return new Claim(id, row.getLeaseToken(), row.getPayloadJson());
    }

    /**
     * 只接受当前租约的结果，避免过期处理者覆盖后续尝试。
     * 租约已更换时静默忽略本次回执。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void acknowledge(Claim claim, boolean success, String errorCode) {
        var row = lock(claim.eventId());
        if (PROCESSING.equals(row.getStatus()) && claim.token().equals(row.getLeaseToken())) {
            finish(row, success, errorCode, clock.now());
        } else {
            log.info("流程业务回执被忽略，租约已过期或更换，eventId={}", claim.eventId());
        }
    }

    /**
     * 落定本次尝试：成功标记 DELIVERED，失败按指数退避重试，耗尽标记 FAILED。
     */
    private void finish(WorkflowDelivery row, boolean success, String errorCode, LocalDateTime now) {
        var retry = !success && row.getAttemptCount() < MAX_ATTEMPTS;
        String failedStatus = retry ? PENDING : FAILED;
        row.setStatus(success ? DELIVERED : failedStatus);
        row.setNextAttemptAt(retry ? now.plusSeconds(5L << (row.getAttemptCount() - 1)) : null);
        row.setLastError(success ? null : errorCode);
        row.setDeliveredAt(success ? now : null);
        row.setLeaseToken(null);
        row.setLeaseExpiresAt(null);
        row.setUpdatedAt(now);
        mapper.update(row, false);
        if (success) {
            log.info("流程业务投递成功，eventId={}，attemptCount={}", row.getEventId(), row.getAttemptCount());
        } else if (retry) {
            log.info("流程业务投递失败待重试，eventId={}，attemptCount={}，nextAttemptAt={}，errorCode={}",
                    row.getEventId(), row.getAttemptCount(), row.getNextAttemptAt(), errorCode);
        } else {
            log.warn("流程业务投递已耗尽重试并标记 FAILED，请按事件 ID 核对业务侧结果和投递回执，修复后在投递管理中重试，eventId={} attemptCount={} maxAttempts={} errorCode={}",
                    row.getEventId(), row.getAttemptCount(), MAX_ATTEMPTS, errorCode);
        }
    }

    private static boolean due(WorkflowDelivery row, LocalDateTime now) {
        return PENDING.equals(row.getStatus()) && row.getNextAttemptAt() != null && !row.getNextAttemptAt().isAfter(now)
                || PROCESSING.equals(row.getStatus()) && row.getLeaseExpiresAt() != null && !row.getLeaseExpiresAt().isAfter(now);
    }

    private WorkflowDelivery lock(long id) {
        var row = mapper.selectOneByQuery(QueryWrapper.create().where(WORKFLOW_DELIVERY.EVENT_ID.eq(id)).forUpdate());
        if (row == null) {
            throw new TodoNotFoundException();
        }
        return row;
    }

    /**
     * 管理端分页查询投递状态，status 为 null 时返回全部。
     */
    Page<DeliveryInfo> list(String status, int page, int size) {
        var rows = mapper.paginate(page, size, QueryWrapper.create().where(WORKFLOW_DELIVERY.STATUS.eq(status, status != null))
                .orderBy(WORKFLOW_DELIVERY.EVENT_ID.desc()));
        var result = new Page<DeliveryInfo>();
        result.setTotal(rows.getTotalRow());
        result.setPageData(rows.getRecords().stream().map(WorkflowBusinessDelivery::info).toList());
        return result;
    }

    /**
     * 重新启用失败事件，保留原 ID 和载荷，尝试次数清零。
     *
     * @throws TodoConflictException 事件不是 FAILED 状态
     */
    @Transactional(rollbackFor = Exception.class)
    public DeliveryInfo retry(long id) {
        var row = lock(id);
        if (!FAILED.equals(row.getStatus())) {
            throw new TodoConflictException("error.flow.state");
        }
        var now = clock.now();
        row.setStatus(PENDING);
        row.setAttemptCount(0);
        row.setNextAttemptAt(now);
        row.setUpdatedAt(now);
        mapper.update(row, false);
        log.info("流程业务投递人工重试，eventId={}", id);
        return info(row);
    }

    private static DeliveryInfo info(WorkflowDelivery row) {
        return new DeliveryInfo(row.getEventId(), row.getStatus(), row.getAttemptCount(), row.getNextAttemptAt(),
                row.getDeliveredAt(), row.getLastError());
    }
}
