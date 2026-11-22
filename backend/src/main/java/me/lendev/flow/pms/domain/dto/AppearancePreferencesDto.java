package me.lendev.flow.pms.domain.dto;

/**
 * 当前账号的界面偏好，随用户详情返回。
 */
public record AppearancePreferencesDto(
        String colorMode,
        String primaryColor,
        String language,
        String layout,
        String tableSize,
        Boolean layoutSettingVisible) {

}
