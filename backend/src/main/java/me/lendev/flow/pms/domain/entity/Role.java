package me.lendev.flow.pms.domain.entity;

import lombok.Data;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;

/**
 * 角色
 *
 * @author lendev
 */
@Data
@Table(value = "role", camelToUnderline = false)
public class Role {

    @Id(keyType = KeyType.Auto)
    private Long id;

    private String code;

    private String name;
    private String nameEn;

    private Boolean enable;

}
