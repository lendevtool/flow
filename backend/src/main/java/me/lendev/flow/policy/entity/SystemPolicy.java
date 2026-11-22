package me.lendev.flow.policy.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 系统策略的持久化实体。
 */
@Getter
@Setter
@Table("sys_system_policy")
public class SystemPolicy {

    @Id
    private String policyCode;
    private String config;
    private Integer schemaVersion;
    private Long revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}
