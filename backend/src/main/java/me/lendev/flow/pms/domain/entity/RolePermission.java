package me.lendev.flow.pms.domain.entity;

import lombok.Data;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;

/**
 * 角色与权限关系
 *
 * @author lendev
 */
@Data
@Table(value = "role_permissions_permission", camelToUnderline = false)
public class RolePermission {

    @Id
    private Long roleId;

    @Id
    private Long permissionId;

}
