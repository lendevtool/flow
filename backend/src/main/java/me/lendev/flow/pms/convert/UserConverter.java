package me.lendev.flow.pms.convert;

import static me.lendev.flow.common.mapstruct.MapstructConstant.DEFAULT_COMPONENT_MODEL;

import me.lendev.flow.pms.domain.dto.UserDetailDto;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import org.mapstruct.Mapper;

/**
 * 用户对象转换
 *
 * @author lendev
 */
@Mapper(componentModel = DEFAULT_COMPONENT_MODEL)
public interface UserConverter {

    UserDetailDto toUserDetailDto(User user);

    User toUser(RegisterUserRequest request);

}
