package me.lendev.flow.pms.domain.request;

import lombok.Data;

/**
 * 更新用户信息
 *
 * @author lendev
 */
@Data
public class UpdateProfileRequest {

    private Long id;

    private Integer gender;

    private String address;

    private String email;

    private String nickName;
}
