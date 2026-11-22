package me.lendev.flow.pms.domain.dto;

import java.util.List;

import lombok.Data;

/**
 * 角色Dto
 *
 * @author lendev
 */
@Data
public class RolePageDto {

    private Long id;

    private String code;

    private String name;
    private String nameEn;

    private Boolean enable;

    private List<Long> permissionIds;

}
