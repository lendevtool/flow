package me.lendev.flow.pms.domain.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 给角色分配用户
 *
 * @author lendev
 */
@Data
public class AddRoleUsersRequest {

    private List<@NotNull @Positive Long> userIds;

}
