package me.lendev.flow.common.validation;

/**
 * 可选英文显示名的存储规则。
 */
public final class EnglishName {

    private EnglishName() {
    }

    /**
     * 去除英文显示名首尾空白，将空值统一存为 NULL。
     *
     * @param value 请求提供的英文名称
     * @return 规范化名称，未填写时为空
     */
    public static String normalizeEnglishName(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
