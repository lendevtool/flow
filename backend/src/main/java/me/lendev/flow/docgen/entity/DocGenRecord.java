package me.lendev.flow.docgen.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.param.support.ParamJsonTypeHandler;
import tools.jackson.databind.JsonNode;

@Getter
@Setter
@Table(value = "biz_doc_generation", camelToUnderline = false)
public class DocGenRecord {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long templateVersionId;
    private Integer createdBy;
    private Long createdByAppId;
    private String requestKey;
    private String requestHash;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode inputJson;
    private String status;
    private String errorCode;
    private Long outputDocumentId;
    private Long outputVersionId;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
