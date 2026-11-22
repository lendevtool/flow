package me.lendev.flow.param.request;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

/**
 * 参数记录新增/编辑请求：value1～value9 对应固定字段存储列，extra 承载扩展字段值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ParamItemRequest extends ParamWriteRequest {

    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value1;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value2;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value3;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value4;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value5;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value6;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value7;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value8;
    @Size(max = 512, message = "{validation.param.fixed-value.size}")
    private String value9;
    /**
     * 扩展字段值 JSON 对象，key 为字段标识。
     */
    private JsonNode extra;
}

