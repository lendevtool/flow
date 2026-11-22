package me.lendev.flow.param.domain;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import me.lendev.flow.param.support.ParamJsonTypeHandler;
import tools.jackson.databind.JsonNode;

/**
 * 参数明细实体，对应表 {@code sys_param_item}： {@code value1}～{@code value9} 为固定字段列，{@code extra} 存放扩展字段值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Table(value = "sys_param_item", camelToUnderline = false)
public class ParamItem extends ParamRecord {

    private Long groupId;
    private String value1;
    private String value2;
    private String value3;
    private String value4;
    private String value5;
    private String value6;
    private String value7;
    private String value8;
    private String value9;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode extra;

    public String value(int index) {
        return switch (index) {
            case 1 -> value1;
            case 2 -> value2;
            case 3 -> value3;
            case 4 -> value4;
            case 5 -> value5;
            case 6 -> value6;
            case 7 -> value7;
            case 8 -> value8;
            case 9 -> value9;
            default -> throw new IllegalArgumentException("Invalid fixed field");
        };
    }
}

