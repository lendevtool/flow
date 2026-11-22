package me.lendev.flow.institution.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import com.mybatisflex.annotation.Column;
import me.lendev.flow.param.support.ParamJsonTypeHandler;
import tools.jackson.databind.JsonNode;

/**
 * 机构变更申请的送审原值和生效结果。
 */
@Getter
@Setter
@Table(value = "biz_institution_change", camelToUnderline = false)
public class InstitutionChangeRow {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long institutionId;
    private Long flowInstanceId;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode baseValues;
    private String status;
    private Long resultEventId;
    private LocalDateTime resolvedAt;
}
