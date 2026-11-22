package me.lendev.flow.pms.domain.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 界面偏好的部分更新请求。省略或传空的字段保留现有值。
 */
public record UpdateAppearanceRequest(
        @Pattern(regexp = "auto|light|dark") String colorMode,
        @Size(max = 64)
        @PrimaryColor
        String primaryColor,
        @Pattern(regexp = "zh-CN|en-US") String language,
        @Pattern(regexp = "simple|normal|full|empty") String layout,
        @Pattern(regexp = "small|medium|large") String tableSize,
        Boolean layoutSettingVisible) {

    /**
     * 判断请求是否包含需要写入的偏好。
     *
     * @return 存在任意非空字段时返回 true
     */
    public boolean hasChanges() {
        return colorMode != null || primaryColor != null || language != null || layout != null
                || tableSize != null || layoutSettingVisible != null;
    }
}
