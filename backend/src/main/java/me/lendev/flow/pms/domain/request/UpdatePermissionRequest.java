package me.lendev.flow.pms.domain.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新权限
 */
@Data
public class UpdatePermissionRequest {

    private String name;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;
    private String code;

    private String type;


    private Long parentId;

    private String path;

    private String redirect;

    private String icon;

    private String component;

    private String layout;

    private Boolean keepAlive;

    private String method;

    private String description;

    private Boolean show;

    private Boolean enable;

    private Integer order;
}
