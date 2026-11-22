package me.lendev.flow.pms.domain.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import me.lendev.flow.common.auth.LoginMethod;
import lombok.ToString;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求参数
 *
 * @author lendev
 */
@Data
public class LoginRequest {

    /**
     * 用户名
     */
    @NotBlank(message = "{validation.username.required}")
    @Size(max = 254)
    private String username;

    /**
     * 密码
     */
    @NotBlank(message = "{validation.login.password.required}")
    @Size(max = 256)
    @ToString.Exclude
    private String password;

    /**
     * 验证码
     */
    @Size(max = 4)
    @ToString.Exclude
    private String captcha;

    @Size(max = 64)
    @ToString.Exclude
    private String captchaKey;

    @NotNull
    private LoginMethod loginMethod = LoginMethod.LOCAL;

    @JsonIgnore
    private String remoteAddress;

    @AssertTrue(message = "{error.request.validation}")
    @JsonIgnore
    public boolean isLocalInputWithinLimits() {
        return loginMethod != LoginMethod.LOCAL
                || (username == null || username.length() <= 50) && (password == null || password.length() <= 72);
    }

}
