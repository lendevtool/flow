package me.lendev.flow.workbench.flow.mapper;

import com.mybatisflex.core.BaseMapper;

import java.time.LocalDateTime;
import java.util.List;

import me.lendev.flow.workbench.flow.entity.WorkflowDelivery;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 业务结果投递表 wf_business_delivery 的数据访问接口，除通用增删改查外提供待投递事件候选查询。
 */
public interface WorkflowDeliveryMapper extends BaseMapper<WorkflowDelivery> {

    /**
     * 查询可投递的事件候选：仅返回到达重试时间的 PENDING 事件和租约过期的 PROCESSING 事件，
     * 且同一流程实例存在更早的未确认事件时跳过，保证实例内事件按 ID 顺序投递。只读查询，不加锁，
     * 领取租约由调用方在短事务中另行完成。
     *
     * @param now   当前时间，用于判断重试到期与租约过期
     * @param limit 单次拉取的最大候选数
     * @return 满足条件的事件 ID 列表，按事件 ID 升序；无候选时为空列表
     */
    @Select("""
            SELECT d.eventId
            FROM wf_business_delivery d JOIN wf_flow_event e ON e.id = d.eventId
            WHERE ((d.status = 'PENDING' AND d.nextAttemptAt <= #{now})
                   OR (d.status = 'PROCESSING' AND d.leaseExpiresAt <= #{now}))
              AND NOT EXISTS (
                  SELECT 1 FROM wf_flow_event previous
                  JOIN wf_business_delivery pending ON pending.eventId = previous.id
                  WHERE previous.flowInstanceId = e.flowInstanceId
                    AND previous.id < e.id AND pending.status <> 'DELIVERED'
              )
            ORDER BY d.eventId LIMIT #{limit}
            """)
    List<Long> eligible(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
