package me.lendev.flow.document.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 文档、业务归属及六个固定槽位的持久化字段。
 */
@Getter
@Setter
@Table(value = "doc_document", camelToUnderline = false)
public class DocumentRow {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String name;
    private Long departmentId;
    private Long productId;
    private String dimension1Value;
    private String dimension2Value;
    private String dimension3Value;
    private String dimension4Value;
    private String dimension5Value;
    private String dimension6Value;
    private String sourceModule;
    private String businessType;
    private String businessId;
    private Long businessVersionId;
    private Integer createdBy;
    private Integer updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
