package me.lendev.flow.pms.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import me.lendev.flow.common.auth.LoginMethod;

/**
 * 用户详细信息
 *
 * @author lendev
 */
@Data
public class UserDetailDto {

    private Long id;

    private String username;
    private String nameCn;
    private String nameEn;
    private String domainAccount;
    private String mobile;
    private LoginMethod loginMethod;

    private Boolean enable;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private RoleDto currentRole;

    private ProfileDto profile;

    private AppearancePreferencesDto appearance;

    private List<RoleDto> roles;

}
