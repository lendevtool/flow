package me.lendev.flow.pms.convert;

import static me.lendev.flow.common.mapstruct.MapstructConstant.DEFAULT_COMPONENT_MODEL;

import me.lendev.flow.pms.domain.dto.RoleDto;
import me.lendev.flow.pms.domain.dto.RolePageDto;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.request.CreateRoleRequest;
import org.mapstruct.Mapper;

/**
 * 角色对象转换
 *
 * @author lendev
 */
@Mapper(componentModel = DEFAULT_COMPONENT_MODEL)
public interface RoleConverter {

    RoleDto toRoleDto(Role role);

    RolePageDto toRolePageDto(Role role);

    Role toRole(CreateRoleRequest request);

}
