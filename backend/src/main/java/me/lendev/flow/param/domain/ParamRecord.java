package me.lendev.flow.param.domain;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 参数记录公共基类：包含编码、名称、启用状态、排序、备注及创建/更新审计字段。
 */
@Data
public abstract class ParamRecord {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String code;
    private String name;
    private String nameEn;
    private Boolean enable;
    private Integer sort;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @Column(ignore = true)
    private String createdByName;
    @Column(ignore = true)
    private String updatedByName;
}

