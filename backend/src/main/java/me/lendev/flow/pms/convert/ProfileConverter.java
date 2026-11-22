package me.lendev.flow.pms.convert;

import static me.lendev.flow.common.mapstruct.MapstructConstant.DEFAULT_COMPONENT_MODEL;

import me.lendev.flow.pms.domain.dto.AppearancePreferencesDto;
import me.lendev.flow.pms.domain.dto.ProfileDto;
import me.lendev.flow.pms.domain.entity.Profile;
import me.lendev.flow.pms.domain.request.RegisterUserProfileRequest;
import me.lendev.flow.pms.domain.request.UpdateAppearanceRequest;
import me.lendev.flow.pms.domain.request.UpdateProfileRequest;
import org.mapstruct.Mapper;

/**
 * 用户资料对象转换
 *
 * @author lendev
 */
@Mapper(componentModel = DEFAULT_COMPONENT_MODEL)
public interface ProfileConverter {

    ProfileDto toProfileDto(Profile profile);

    /**
     * 读取资料中的界面偏好。
     *
     * @param profile 用户资料
     * @return 界面偏好
     */
    AppearancePreferencesDto toAppearance(Profile profile);

    /**
     * 将已提交的偏好映射到资料字段，保留未提交字段的空值。
     *
     * @param request 偏好更新请求
     * @return 用于部分更新的用户资料
     */
    Profile toProfile(UpdateAppearanceRequest request);

    Profile toProfile(RegisterUserProfileRequest request);

    Profile toProfile(UpdateProfileRequest request);

}
