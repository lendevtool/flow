package me.lendev.flow.pms.domain.request;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 创建角色
 *
 * @author lendev
 */
@Data
public class CreateRoleRequest {

    @NotBlank(message = "{validation.role.code.required}")
    private String code;

    @NotBlank(message = "{validation.role.name.required}")
    private String name;

    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;
    private List<@NotNull @Positive Long> permissionIds;

    private Boolean enable;

}
