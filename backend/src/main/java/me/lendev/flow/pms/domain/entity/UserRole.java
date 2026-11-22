package me.lendev.flow.pms.domain.entity;

import lombok.Data;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;

/**
 * 用户与角色关系表
 *
 * @author lendev
 */
@Data
@Table(value = "user_roles_role", camelToUnderline = false)
public class UserRole {

    @Id
    private Long userId;

    @Id
    private Long roleId;

}
