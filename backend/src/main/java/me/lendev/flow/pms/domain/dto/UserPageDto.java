package me.lendev.flow.pms.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import me.lendev.flow.pms.department.DepartmentModels.DepartmentRef;

/**
 * 用户分页数据
 */
@Data
public class UserPageDto {

    private Long id;

    private String username;
    private String nameCn;
    private String nameEn;
    private String domainAccount;
    private String mobile;

    private String nickName;

    private Boolean enable;

    private Long departmentId;

    private String departmentName;

    private Long departmentVersion;

    private List<DepartmentRef> departments;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer gender;

    private String avatar;

    private String address;

    private String email;

    private List<RoleDto> roles;


}
