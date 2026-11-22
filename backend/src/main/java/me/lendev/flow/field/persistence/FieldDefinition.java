package me.lendev.flow.field.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import me.lendev.flow.field.FieldModels.DataType;
import me.lendev.flow.field.FieldModels.TextFormat;

/**
 * 字段定义的持久化实体，默认值以 JSON 文本存储。
 */
@Data
@Table(value = "sys_field_definition", camelToUnderline = false)
public class FieldDefinition {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String code;
    private String name;
    private String nameEn;
    private DataType dataType;
    private Boolean required;
    private String defaultValue;
    private Integer textMinLength;
    private Integer textMaxLength;
    private TextFormat textFormat;
    private String textPattern;
    private Boolean textCharLimitEnabled;
    private Boolean textAllowChinese;
    private Boolean textAllowEnglish;
    private Boolean textAllowDigits;
    private Boolean textAllowSpace;
    private Boolean textAllowSeparators;
    private Boolean textAllowChinesePunctuation;
    private Boolean textAllowEnglishPunctuation;
    private Boolean numberPositiveOnly;
    private String integerMin;
    private String integerMax;
    private String decimalMin;
    private String decimalMax;
    private Integer decimalPrecision;
    private Integer decimalScale;
    private LocalDate dateMin;
    private LocalDate dateMax;
    private LocalDateTime dateTimeMin;
    private LocalDateTime dateTimeMax;
    private Long paramGroupId;
    private Integer enumMinItems;
    private Integer enumMaxItems;
    private Boolean enable;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    @Column(onUpdateValue = "CURRENT_TIMESTAMP(3)")
    private LocalDateTime updatedAt;
}
