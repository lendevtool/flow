package me.lendev.flow.institution.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 机构档案的生效数据。
 */
@Getter
@Setter
@Table(value = "biz_institution", camelToUnderline = false)
public class InstitutionRow {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String code;
    private String nameZh;
    private String nameEn;
    private Long typeItemId;
    private Long countryRegionItemId;
    private String registrationId;
    private String shortNameZh;
    private String shortNameEn;
    private String officeAddress;
    private String website;
    private String remark;
    private Boolean enabled;
    private Long version;
    private Integer createdBy;
    private Integer updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
