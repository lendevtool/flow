package me.lendev.flow.pms.convert;

import static me.lendev.flow.common.mapstruct.MapstructConstant.DEFAULT_COMPONENT_MODEL;

import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.request.CreatePermissionRequest;
import me.lendev.flow.pms.domain.request.UpdatePermissionRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 权限对象转换
 *
 * @author lendev
 */
@Mapper(componentModel = DEFAULT_COMPONENT_MODEL)
public interface PermissionConverter {

    PermissionDto toPermissionDto(Permission permission);

    @Mapping(target = "keepAlive", source = "keepalive")
    Permission toPermission(CreatePermissionRequest request);

    Permission toPermission(UpdatePermissionRequest request);

}
