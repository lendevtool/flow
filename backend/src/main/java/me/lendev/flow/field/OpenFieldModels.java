package me.lendev.flow.field;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 字段管理专用开放 API v1 的查询参数和公开响应契约。
 *
 * <p>与员工接口 DTO 分开维护。字段响应只包含标识和业务配置，不携带创建、更新人员及时间，
 * 也不包含员工接口的 {@code R} 包装。可空配置保留原业务查询的含义，不在响应适配时补造业务值。
 */
public final class OpenFieldModels {
    private OpenFieldModels() {
    }

    /**
     * 字段业务数据类型，用于筛选字段及解释其配置和默认值。
     * 数值字段的业务值使用字符串保留精度，日期、日期时间使用不带时区的 ISO 格式。
     */
    @Schema(name = "OpenFieldDataType")
    public enum DataType {
        /**
         * 文本，适用长度、格式、字符类别和正则校验规则。
         */
        TEXT,
        /**
         * 整数，业务值以字符串表达，适用整数边界及正数限制。
         */
        INTEGER,
        /**
         * 小数，业务值以字符串表达，适用数值边界、精度、小数位数及正数限制。
         */
        DECIMAL,
        /**
         * 布尔值，使用 JSON 的 true 或 false。
         */
        BOOLEAN,
        /**
         * 日期，使用 yyyy-MM-dd 格式。
         */
        DATE,
        /**
         * 不带时区的日期时间，精确到秒，使用 yyyy-MM-dd'T'HH:mm:ss 格式。
         */
        DATETIME,
        /**
         * 单选枚举，业务值为所引用参数字典的选项编码字符串。
         */
        ENUM,
        /**
         * 多选枚举，业务值为不重复的选项编码数组，可限制选项数量。
         */
        ENUM_MULTI,
        /**
         * 附件，业务值为包含 documentId、versionId 字符串的引用对象数组。
         */
        ATTACHMENT
    }

    /**
     * 文本字段的内置格式校验类型，仅在字段类型为 {@link DataType#TEXT} 时适用。
     * 配置为 {@code null} 表示不使用内置格式校验，其他文本规则仍独立生效。
     */
    @Schema(name = "OpenFieldTextFormat")
    public enum TextFormat {
        /**
         * 电子邮箱地址格式。
         */
        EMAIL,
        /**
         * URL 地址格式，按字段业务校验器允许的协议和地址规则校验。
         */
        URL
    }

    /**
     * 字段定义分页查询条件，各组件均可省略，以同名查询参数传入。
     *
     * <p>筛选条件同时生效，结果按字段 ID 倒序排列。默认页码和页大小由业务查询处理，
     * 本 DTO 保留 {@code null}，不在构造时替换默认值。
     *
     * @param keyword  字段编码、中文名或英文名的模糊查询关键词，最多 100 字符；
     *                 {@code null}、空串或空白表示不按关键词筛选，查询忽略大小写并移除首尾空白
     * @param dataType 字段数据类型；{@code null} 表示不按类型筛选
     * @param enable   启用状态；{@code true} 查询启用字段，{@code false} 查询停用字段，
     *                 {@code null} 表示不按状态筛选
     * @param pageNo   从 1 开始的正整数页码；{@code null} 时业务查询默认使用 1
     * @param pageSize 每页记录数，范围 1～100；{@code null} 时业务查询默认使用 20
     */
    @Schema(name = "OpenFieldQuery")
    public record Query(@Size(max = 100) String keyword, DataType dataType, Boolean enable,
                        @Positive Integer pageNo, @Min(1) @Max(100) Integer pageSize) {
    }

    /**
     * 字段的完整公开业务配置，不包含人员和时间审计信息。
     *
     * <p>各类型只使用对应的校验规则。可空的规则值表示未配置或不适用于当前类型；
     * 字符类别开关仅在 {@code textCharLimitEnabled} 为 {@code true} 时参与文本校验。
     * 数值上下界使用字符串保留精度，日期和日期时间上下界均包含边界值。
     *
     * @param code                        字段唯一业务编码，小写字母开头，可包含小写字母、数字和下划线，最长 64 字符
     * @param name                        字段中文名称，最长 100 字符
     * @param nameEn                      字段英文名称，最长 100 字符
     * @param dataType                    字段业务数据类型，决定默认值格式及适用的校验规则
     * @param required                    字段值是否必填；{@code false} 表示允许未填写
     * @param defaultValue                按字段类型保存的 JSON 默认值；未设置时为 {@code null}，
     *                                    文本、数值、日期和枚举等按原业务格式返回，布尔值及数组保留 JSON 类型
     * @param textMinLength               文本最小长度，按 Unicode 码点计数，包含边界；{@code null} 表示未配置
     * @param textMaxLength               文本最大长度，按 Unicode 码点计数，包含边界；{@code null} 表示未配置
     * @param textFormat                  文本内置格式校验类型；{@code null} 表示不使用内置格式校验
     * @param textPattern                 文本全文匹配的正则校验规则；{@code null} 或空白表示未配置
     * @param textCharLimitEnabled        是否按以下字符类别开关限制文本可用字符
     * @param textAllowChinese            字符类别限制开启时，是否允许汉字
     * @param textAllowEnglish            字符类别限制开启时，是否允许英文字母 A～Z、a～z
     * @param textAllowDigits             字符类别限制开启时，是否允许数字 0～9
     * @param textAllowSpace              字符类别限制开启时，是否允许普通半角空格
     * @param textAllowSeparators         字符类别限制开启时，是否允许连字符和下划线
     * @param textAllowChinesePunctuation 字符类别限制开启时，是否允许业务规则定义的中文标点
     * @param textAllowEnglishPunctuation 字符类别限制开启时，是否允许业务规则定义的英文标点
     * @param numberPositiveOnly          整数或小数是否必须严格大于 0；{@code false} 时仍需遵守配置的上下界
     * @param integerMin                  整数最小值的字符串表示，包含边界；{@code null} 表示未配置业务下界
     * @param integerMax                  整数最大值的字符串表示，包含边界；{@code null} 表示未配置业务上界
     * @param decimalMin                  小数最小值的字符串表示，包含边界；{@code null} 表示未配置业务下界
     * @param decimalMax                  小数最大值的字符串表示，包含边界；{@code null} 表示未配置业务上界
     * @param decimalPrecision            小数最多允许的总有效位数，配置范围 1～38；
     *                                    {@code null} 时业务校验使用 38 位
     * @param decimalScale                小数最多允许的小数位数，配置范围 0～18；
     *                                    {@code null} 时业务校验使用 18 位；同时配置精度时不能大于精度
     * @param dateMin                     日期最小值，包含边界；{@code null} 表示未配置
     * @param dateMax                     日期最大值，包含边界；{@code null} 表示未配置
     * @param dateTimeMin                 不带时区、精确到秒的日期时间最小值，包含边界；{@code null} 表示未配置
     * @param dateTimeMax                 不带时区、精确到秒的日期时间最大值，包含边界；{@code null} 表示未配置
     * @param paramGroupId                单选、多选枚举引用的参数字典分类 ID；其他类型为 {@code null}
     * @param enumMinItems                多选枚举最少选择数量，非负整数，包含边界；{@code null} 表示未配置
     * @param enumMaxItems                多选枚举最多选择数量，非负整数，包含边界；{@code null} 表示未配置
     * @param enable                      字段是否启用；查询未按状态筛选时也可能返回停用字段
     * @param remark                      字段业务备注，最长 500 字符；可以为 {@code null} 或空串
     */
    @Schema(name = "OpenFieldConfig")
    public record Config(
            String code, String name, String nameEn, DataType dataType, boolean required,
            @Schema(description = "按字段类型保存的任意 JSON 默认值，可为标量、数组或 null") JsonNode defaultValue,
            Integer textMinLength, Integer textMaxLength, TextFormat textFormat, String textPattern,
            boolean textCharLimitEnabled, boolean textAllowChinese, boolean textAllowEnglish,
            boolean textAllowDigits, boolean textAllowSpace, boolean textAllowSeparators,
            boolean textAllowChinesePunctuation, boolean textAllowEnglishPunctuation,
            boolean numberPositiveOnly, String integerMin, String integerMax, String decimalMin, String decimalMax,
            Integer decimalPrecision, Integer decimalScale, LocalDate dateMin, LocalDate dateMax,
            LocalDateTime dateTimeMin, LocalDateTime dateTimeMax, Long paramGroupId,
            Integer enumMinItems, Integer enumMaxItems, boolean enable, String remark) {
    }

    /**
     * 字段详情及分页列表共用的公开字段定义，只返回标识和业务配置。
     *
     * @param id     字段定义 ID，正整数，不是字典分类或字典选项 ID
     * @param config 完整业务配置，包含当前类型对应的默认值和校验规则，不包含审计信息
     */
    @Schema(name = "OpenFieldDefinition")
    public record Definition(long id, Config config) {
    }

    /**
     * 字段定义分页结果，直接作为成功响应返回，不使用员工接口的响应包装。
     *
     * @param pageData 当前页的字段定义列表，按字段 ID 倒序；无匹配记录或超出末页时为空列表，
     *                 不为 {@code null} 且不包含 {@code null} 元素
     * @param total    符合所有筛选条件的总记录数，不是当前页条数；超出末页时仍保留实际匹配总数
     */
    @Schema(name = "OpenFieldPage")
    public record FieldPage(List<Definition> pageData, long total) {
        /**
         * 复制当前页列表，避免调用方修改原列表后影响已构造的分页结果。
         *
         * @param pageData 当前页列表，不能为空引用或包含 {@code null} 元素
         * @param total    符合筛选条件的总记录数
         * @throws NullPointerException 列表或其任意元素为 {@code null}
         */
        public FieldPage {
            pageData = List.copyOf(pageData);
        }
    }

    /**
     * 字段可引用的参数字典分类；列表接口也可能返回调用方明确指定的已选停用字典。
     *
     * @param id     参数字典分类 ID，用于枚举配置引用和字典选项查询
     * @param code   字典分类业务编码
     * @param name   字典分类中文名称
     * @param nameEn 字典分类英文名称，可以为 {@code null}
     * @param enable 字典分类是否启用；{@code false} 表示已停用，不代表其明细已被删除
     */
    @Schema(name = "OpenFieldDictionary")
    public record Dictionary(long id, String code, String name, String nameEn, boolean enable) {
    }

    /**
     * 参数字典选项或字段元数据选项，不暴露参数明细的内部 ID。
     *
     * @param code   选项业务编码；用于枚举值，或标识字段类型、文本格式、字符类别
     * @param name   选项中文名称
     * @param nameEn 选项英文名称，可以为 {@code null}
     * @param enable 选项是否启用；普通字典选项表示明细本身的状态，
     *               字段元数据选项还需其所属参数分类处于启用状态
     */
    @Schema(name = "OpenFieldOption")
    public record Option(String code, String name, String nameEn, boolean enable) {
    }

    /**
     * 按用途分组的字段元数据，仅包含字段模块支持的编码，保留各选项的启用状态。
     *
     * @param types      字段数据类型选项，对应 {@link DataType}；未配置时为空列表
     * @param formats    文本内置格式选项，对应 {@link TextFormat}；未配置时为空列表
     * @param characters 文本允许字符类别选项，编码对应各字符类别配置开关；未配置时为空列表
     */
    @Schema(name = "OpenFieldMetadata")
    public record Metadata(List<Option> types, List<Option> formats, List<Option> characters) {
        /**
         * 复制各分组列表，避免原列表的后续变动影响返回的元数据。
         *
         * @param types      字段数据类型选项列表
         * @param formats    文本格式选项列表
         * @param characters 字符类别选项列表
         * @throws NullPointerException 任一列表或列表中的任意元素为 {@code null}
         */
        public Metadata {
            types = List.copyOf(types);
            formats = List.copyOf(formats);
            characters = List.copyOf(characters);
        }
    }
}
