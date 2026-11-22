package me.lendev.flow.pms.domain.dto;

import lombok.Data;

/**
 * 角色Dto
 *
 * @author lendev
 */
@Data
public class RoleDto {

    private Long id;

    private String code;

    private String name;
    private String nameEn;

    private Boolean enable;

}
