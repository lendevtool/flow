package me.lendev.flow.pms.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import jakarta.validation.constraints.Size;
import lombok.ToString;

/**
 * 修改密码请求
 *
 * @author lendev
 */
@Data
public class ChangePasswordRequest {


    @NotBlank(message = "{validation.password.current.required}")
    @Size(max = 72)
    @ToString.Exclude
    private String oldPassword;

    @NotBlank(message = "{validation.password.new.required}")
    @ToString.Exclude
    private String newPassword;

}
