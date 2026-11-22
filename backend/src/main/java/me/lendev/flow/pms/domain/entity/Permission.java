package me.lendev.flow.pms.domain.entity;

import lombok.Data;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;

/**
 * 权限
 *
 * @author lendev
 */
@Data
@Table(value = "permission", camelToUnderline = false)
public class Permission {

    @Id(keyType = KeyType.Auto)
    private Long id;

    private String name;
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
