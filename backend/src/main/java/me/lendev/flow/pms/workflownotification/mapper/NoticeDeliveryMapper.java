package me.lendev.flow.pms.workflownotification.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.pms.workflownotification.persistence.NoticeDelivery;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 流程通知主行访问及领取条件。
 */
public interface NoticeDeliveryMapper extends BaseMapper<NoticeDelivery> {

    String DUE = """
            (status = 'PENDING'
                OR (status = 'RETRY' AND next_attempt_at <= #{now})
                OR (status = 'PROCESSING' AND lease_expires_at <= #{now}))
            """;

    /**
     * 按 ID 获取有限的可领取投递，领取时仍须重验条件。
     */
    @Select("SELECT id FROM wf_notification_delivery WHERE " + DUE + " ORDER BY id LIMIT #{limit}")
    List<Long> eligible(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 条件领取一个新租约；并发领取者只能有一个成功。
     */
    @Update("""
            UPDATE wf_notification_delivery
            SET status = 'PROCESSING', attempt_count = #{count}, next_attempt_at = NULL,
                lease_token = #{token}, lease_expires_at = #{expires}, updated_at = #{now}
            WHERE id = #{id} AND
            """ + DUE)
    int claimIfDue(@Param("id") long id, @Param("now") LocalDateTime now,
                   @Param("expires") LocalDateTime expires, @Param("token") String token, @Param("count") int count);

    /**
     * 在首次固定待办邮件参数时确认节点、任务和统一待办仍可办理。
     */
    @Select("""
            SELECT COUNT(*)
            FROM wf_node_task t
            JOIN wf_node_instance n ON n.id = t.nodeInstanceId
            JOIN wf_instance i ON i.id = n.flowInstanceId
            JOIN wb_todo w ON w.id = t.todoId
            WHERE t.id = #{taskId} AND t.assigneeUserId = #{userId} AND t.processedAt IS NULL
              AND i.status = 'RUNNING' AND n.status = 'ACTIVE' AND n.stageNo = i.currentStepNo
              AND w.status IN ('OPEN', 'IN_PROGRESS')
            """)
    int openTask(@Param("taskId") long taskId, @Param("userId") int userId);
}
