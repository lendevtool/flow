package me.lendev.flow.param.request;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

/**
 * 参数组新增/编辑请求，在公共写请求基础上携带动态字段配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ParamGroupRequest extends ParamWriteRequest {

    /**
     * 动态字段配置 JSON（固定字段与扩展字段的 schema），服务端会重新校验。
     */
    private JsonNode fieldConfig;
}

