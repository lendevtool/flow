package me.lendev.flow.pms.workflownotification;

import static me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.*;
import static me.lendev.flow.pms.workflownotification.persistence.table.NoticeDeliveryTableDef.NOTICE_DELIVERY;
import static me.lendev.flow.pms.workflownotification.persistence.table.NoticeRecipientTableDef.NOTICE_RECIPIENT;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.workflownotification.mapper.NoticeDeliveryMapper;
import me.lendev.flow.pms.workflownotification.mapper.NoticeRecipientMapper;
import me.lendev.flow.pms.workflownotification.persistence.NoticeDelivery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询投递历史，响应只包含投递元数据及可跟踪的关联 ID。
 */
@Service
@RequiredArgsConstructor
class WorkflowNoticeQueries {

    private final NoticeDeliveryMapper deliveries;
    private final NoticeRecipientMapper recipients;

    Page<DeliveryRow> list(Query filter) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new BadRequestException("投递起始时间必须早于结束时间");
        }
        var query = QueryWrapper.create()
                .where(NOTICE_DELIVERY.FLOW_INSTANCE_ID.eq(filter.flowInstanceId(), filter.flowInstanceId() != null))
                .and(NOTICE_DELIVERY.SCENE_CODE.eq(filter.sceneCode(), filter.sceneCode() != null))
                .and(NOTICE_DELIVERY.STATUS.eq(filter.status(), filter.status() != null))
                .and(NOTICE_DELIVERY.CREATED_AT.ge(utc(filter.from()), filter.from() != null))
                .and(NOTICE_DELIVERY.CREATED_AT.lt(utc(filter.to()), filter.to() != null))
                .orderBy(NOTICE_DELIVERY.ID.desc());
        var rows = deliveries.paginate(filter.pageNo() == null ? 1 : filter.pageNo(),
                filter.pageSize() == null ? 20 : filter.pageSize(), query);
        var page = new Page<DeliveryRow>();
        page.setPageData(rows.getRecords().stream().map(WorkflowNoticeQueries::view).toList());
        page.setTotal(rows.getTotalRow());
        return page;
    }

    /**
     * 在同一快照内读取批次及各渠道状态；投递不存在时返回空。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long id) {
        var delivery = deliveries.selectOneById(id);
        if (delivery == null) {
            return null;
        }
        var entries = recipients.selectListByQuery(QueryWrapper.create().where(NOTICE_RECIPIENT.DELIVERY_ID.eq(id))
                .orderBy(NOTICE_RECIPIENT.RECIPIENT_USER_ID.asc()));
        return new Detail(view(delivery), entries.stream().map(row -> new RecipientRow(row.getRecipientUserId(),
                row.getInAppStatus(), row.getMessageId(), row.getMailStatus(), row.getMailLogId(),
                row.getInAppErrorCode(), row.getMailErrorCode())).toList());
    }

    private static DeliveryRow view(NoticeDelivery row) {
        return new DeliveryRow(row.getId(), row.getFlowInstanceId(), row.getNodeInstanceId(), row.getEventId(), row.getTaskId(),
                row.getSceneCode(), row.getKind(), row.getStatus(), row.getAttemptCount(), row.getSceneVersion(),
                instant(row.getCreatedAt()), instant(row.getPreparedAt()), instant(row.getFinishedAt()), row.getLastErrorCode());
    }

    private static LocalDateTime utc(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
