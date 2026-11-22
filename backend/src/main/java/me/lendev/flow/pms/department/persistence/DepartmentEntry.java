package me.lendev.flow.pms.department.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * 部门的持久化实体。负责人名称由查询投影提供。
 */
@Data
@Table("sys_department")
public class DepartmentEntry {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long parentId;
    private String name;
    private Integer sortOrder;
    private Boolean enabled;
    private Long leaderUserId;
    @Column(onUpdateValue = "CURRENT_TIMESTAMP(6)")
    private LocalDateTime updatedAt;
    @Column(ignore = true)
    private String leaderName;
}
