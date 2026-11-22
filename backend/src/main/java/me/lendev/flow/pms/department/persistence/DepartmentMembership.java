package me.lendev.flow.pms.department.persistence;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * 用户与部门的归属关系，两个外键共同组成主键。
 */
@Data
@Table("sys_department_member")
public class DepartmentMembership {

    @Id
    private Long userId;
    @Id
    private Long departmentId;
}
