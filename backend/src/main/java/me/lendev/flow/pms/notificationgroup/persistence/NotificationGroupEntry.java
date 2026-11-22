package me.lendev.flow.pms.notificationgroup.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * 通知群组主表实体，用于创建组并取得数据库生成的主键。
 */
@Data
@Table("sys_notification_group")
public class NotificationGroupEntry {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String name;
    private String description;
    private Boolean enabled;
    private Long version;
    private Integer createdBy;
    private Integer updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
