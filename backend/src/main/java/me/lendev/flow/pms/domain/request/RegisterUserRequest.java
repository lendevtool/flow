package me.lendev.flow.pms.domain.request;

import jakarta.validation.constraints.Size;

import java.util.List;

import jakarta.validation.Valid;
import me.lendev.flow.pms.department.DepartmentModels.InitialMemberships;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.ToString;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.Length;

/**
 * 注册用户
 *
 * @author lendev
 */
@Data
public class RegisterUserRequest {

    @Length(min = 6, max = 20, message = "{validation.username.size}")
    @NotBlank(message = "{validation.username.required}")
    private String username;

    @Size(max = 100, message = "{validation.name-cn.size}")
    private String nameCn;

    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;

    @Size(max = 254, message = "{validation.domain-account.size}")
    private String domainAccount;

    @Size(max = 16, message = "{validation.mobile.size}")
    @ToString.Exclude
    private String mobile;
    /**
     * 仅由应用初始化代码提供。页面建号时由服务端生成临时密码。
     */
    @JsonIgnore
    @ToString.Exclude
    private String password;

    @JsonIgnore
    private boolean bootstrap;

    private Boolean enable;

    /**
     * 可选的部门归属；编辑时省略该字段则保留现有归属。
     */
    @Valid
    private InitialMemberships memberships;

    private RegisterUserProfileRequest profile;

    private List<@NotNull @Positive Long> roleIds;


}
