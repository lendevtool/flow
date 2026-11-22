package me.lendev.flow.param.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 参数组与参数记录共用的写入请求基类，包含编码、名称、状态、排序、备注等公共字段。
 */
@Data
public class ParamWriteRequest {

    @NotBlank(message = "{validation.param.code.required}")
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}", message = "{validation.param.code.format}")
    private String code;
    @NotBlank(message = "{validation.param.name.required}")
    @Size(max = 100, message = "{validation.param.name.size}")
    private String name;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Size(max = 100, message = "{validation.name-en.size}")
    private String nameEn;
    @NotNull(message = "{validation.param.status.required}")
    private Boolean enable = true;
    @NotNull(message = "{validation.param.sort.required}")
    @Min(value = 0, message = "{validation.param.sort.min}")
    private Integer sort = 0;
    @Size(max = 500, message = "{validation.param.notes.size}")
    private String remark;
}

