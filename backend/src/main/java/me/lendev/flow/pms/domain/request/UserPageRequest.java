package me.lendev.flow.pms.domain.request;

import me.lendev.flow.common.request.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询
 *
 * @author lendev
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageRequest extends PageRequest {

    private String username;

    private String keyword;

    private Integer gender;

    private Integer role;

    private Long roleId;

    /**
     * 仅在指定角色 ID 时生效；false 查询未分配该角色的用户。
     */
    private boolean roleAssigned = true;

    private Boolean enable;

    public enum DepartmentScope {
        PRIMARY, ANY
    }

    private Long departmentId;

    private DepartmentScope departmentScope;

    private boolean unassigned;


}
