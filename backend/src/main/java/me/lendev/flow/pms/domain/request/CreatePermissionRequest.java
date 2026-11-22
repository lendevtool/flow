package me.lendev.flow.pms.domain.request;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建权限
 *
 * @author lendev
 */
@Data
public class CreatePermissionRequest {

    @NotBlank
    private String name;

    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;
    @NotBlank
    private String code;

    @NotBlank
    private String type;


    private Long parentId;

    private String path;

    private String redirect;

    private String icon;

    private String component;

    private String layout;

    private Boolean keepalive;

    private String method;

    private String description;

    private Boolean show;

    private Boolean enable;

    private Integer order;
}
