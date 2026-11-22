package me.lendev.flow.pms.domain.entity;

import lombok.Data;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;

/**
 * 用户信息
 *
 * @author lendev
 */
@Data
@Table(value = "profile", camelToUnderline = false)
public class Profile {

    @Id(keyType = KeyType.Auto)
    private Long id;

    private Integer gender;

    private String avatar;

    private String address;

    private String email;

    private Long userId;

    private String nickName;

    private String colorMode;

    private String primaryColor;

    private String language;

    private String layout;

    private String tableSize;

    private Boolean layoutSettingVisible;

}
