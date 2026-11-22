package me.lendev.flow.param.domain;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import me.lendev.flow.param.support.ParamJsonTypeHandler;
import tools.jackson.databind.JsonNode;

/**
 * 参数分类实体，对应表 {@code sys_param_group}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Table(value = "sys_param_group", camelToUnderline = false)
public class ParamGroup extends ParamRecord {

    /**
     * 版本号，明细变动时递增，用于查找缓存失效
     */
    private Long version;
    /**
     * 字段配置 JSON：定义固定字段与扩展字段的标签、类型、选项及默认值
     */
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode fieldConfig;
}

