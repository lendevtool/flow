package me.lendev.flow.field;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

/**
 * 字段定义、查询及字典选项的接口契约。
 */
public final class FieldModels {

    private FieldModels() {
    }

    /**
     * 字段数据类型，决定适用的取值规则。
     */
    public enum DataType {
        TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, ENUM_MULTI, ATTACHMENT;

        boolean isEnum() {
            return this == ENUM || this == ENUM_MULTI;
        }
    }

    /**
     * 文本类型字段的内置格式校验。
     */
    public enum TextFormat {
        EMAIL, URL
    }

    /**
     * 字段定义的完整配置；紧凑构造器把可空布尔开关统一归一为非空值。
     */
    public record Config(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String code,
            @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 100) String nameEn,
            @NotNull DataType dataType, Boolean required, JsonNode defaultValue,
            @PositiveOrZero Integer textMinLength, @PositiveOrZero Integer textMaxLength,
            TextFormat textFormat, @Size(max = 512) String textPattern, Boolean textCharLimitEnabled,
            Boolean textAllowChinese, Boolean textAllowEnglish, Boolean textAllowDigits, Boolean textAllowSpace,
            Boolean textAllowSeparators, Boolean textAllowChinesePunctuation, Boolean textAllowEnglishPunctuation,
            Boolean numberPositiveOnly, @Size(max = 21) String integerMin, @Size(max = 21) String integerMax,
            @Size(max = 40) String decimalMin, @Size(max = 40) String decimalMax,
            @Min(1) @Max(38) Integer decimalPrecision, @Min(0) @Max(18) Integer decimalScale,
            LocalDate dateMin, LocalDate dateMax, LocalDateTime dateTimeMin, LocalDateTime dateTimeMax,
            @Positive Long paramGroupId, @PositiveOrZero Integer enumMinItems, @PositiveOrZero Integer enumMaxItems,
            @NotNull Boolean enable, @Size(max = 500) String remark) {

        public Config {
            required = Boolean.TRUE.equals(required);
            textCharLimitEnabled = Boolean.TRUE.equals(textCharLimitEnabled);
            textAllowChinese = Boolean.TRUE.equals(textAllowChinese);
            textAllowEnglish = Boolean.TRUE.equals(textAllowEnglish);
            textAllowDigits = Boolean.TRUE.equals(textAllowDigits);
            textAllowSpace = Boolean.TRUE.equals(textAllowSpace);
            textAllowSeparators = Boolean.TRUE.equals(textAllowSeparators);
            textAllowChinesePunctuation = Boolean.TRUE.equals(textAllowChinesePunctuation);
            textAllowEnglishPunctuation = Boolean.TRUE.equals(textAllowEnglishPunctuation);
            numberPositiveOnly = Boolean.TRUE.equals(numberPositiveOnly);
        }
    }

    /**
     * 已保存的字段定义及其创建、更新审计信息。
     */
    public record Definition(long id, Config config, Long createdBy, LocalDateTime createdAt,
                             Long updatedBy, LocalDateTime updatedAt) {

    }

    /**
     * 字段取值试测的输入。
     */
    public record ValidationRequest(JsonNode value) {

    }

    /**
     * 试测结果；失败时携带错误码和按请求语言渲染的提示。
     */
    public record ValidationResult(boolean valid, String code, String message) {

    }

    /**
     * 字段定义分页查询条件，页大小为空时默认 20。
     */
    public record Query(@Size(max = 100) String keyword, DataType dataType, Boolean enable,
                        @Positive Integer pageNo, @Min(1) @Max(100) Integer pageSize) {

        int size() {
            return pageSize == null ? 20 : pageSize;
        }
    }

    /**
     * 枚举字段引用的参数字典分类。
     */
    public record Dictionary(long id, String code, String name, String nameEn, boolean enable) {

    }

    /**
     * 字典下的单个选项。
     */
    public record Option(String code, String name, String nameEn, boolean enable) {

    }

    /**
     * 字段类型、文本格式和字符类别的下拉元数据。
     */
    public record Metadata(List<Option> types, List<Option> formats, List<Option> characters) {

    }
}
