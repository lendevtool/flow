package me.lendev.flow.pms.domain.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 添加角色权限
 *
 * @author lendev
 */
@Data
public class AddRolePermissionsRequest {

    @NotNull
    @Positive
    private Long id;

    private List<@NotNull @Positive Long> permissionIds;
}
