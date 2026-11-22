package me.lendev.flow.pms.domain.entity;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.ToString;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;

/**
 * 用户
 *
 * @author lendev
 */
@Data
@Table(value = "user", camelToUnderline = false)
public class User {

    @Id(keyType = KeyType.Auto)
    private Long id;

    private String username;
    private String nameCn;
    private String nameEn;
    private String domainAccount;
    @ToString.Exclude
    private String mobile;

    @ToString.Exclude
    private String password;

    private LocalDateTime passwordChangedAt;
    private LocalDateTime temporaryPasswordExpiresAt;
    private Boolean passwordChangeRequired;

    /**
     * 随机生成的凭据标识（密码代次）：修改/重置密码或禁用用户时轮换，使旧 Token 失效；绝不出现在 API DTO 中。
     */
    @ToString.Exclude
    private String identityKey;

    private Boolean enable;

    private Long departmentId;

    private Long departmentVersion;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
