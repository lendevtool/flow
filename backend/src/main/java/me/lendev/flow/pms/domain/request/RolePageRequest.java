package me.lendev.flow.pms.domain.request;

import me.lendev.flow.common.request.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分页
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RolePageRequest extends PageRequest {

    private String name;

    private Boolean enable;

}
