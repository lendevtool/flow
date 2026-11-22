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
@Table(value = "biz_doc_template_version", camelToUnderline = false)
public class DocGenVersion {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long templateId;
    private Integer versionNo;
    private String status;
    private String templateFormat;
    private Long documentId;
    private Long documentVersionId;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode inputSchema;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode exampleJson;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode renderConfig;
    @Column(typeHandler = ParamJsonTypeHandler.class)
    private JsonNode samplesJson;
    private Integer createdBy;
    private Integer updatedBy;
    private Integer publishedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
}
