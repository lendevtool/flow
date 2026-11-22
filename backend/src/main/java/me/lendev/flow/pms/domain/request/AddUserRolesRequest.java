package me.lendev.flow.pms.domain.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 给用户分配角色
 *
 * @author lendev
 */
@Data
public class AddUserRolesRequest {

    private List<@NotNull @Positive Long> roleIds;

}
