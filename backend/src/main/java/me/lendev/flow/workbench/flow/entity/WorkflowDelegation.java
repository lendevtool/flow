package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 用户委托关系的持久化实体，对应 wf_user_delegation 表，记录委托人、受托人及委托的生效、结束与取消时间。
 */
@Getter
@Setter
@Table(value = "wf_user_delegation", camelToUnderline = false)
public class WorkflowDelegation {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Integer ownerUserId;
    private Integer delegateUserId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
}
