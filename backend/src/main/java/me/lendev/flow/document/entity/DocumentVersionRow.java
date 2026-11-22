package me.lendev.flow.document.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 文档文件版本及远端文件清理状态。
 */
@Getter
@Setter
@Table(value = "doc_document_version", camelToUnderline = false)
public class DocumentVersionRow {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long documentId;
    private LocalDate versionDate;
    private Integer dailySequence;
    private String originalFileName;
    private String contentType;
    private Long sizeBytes;
    private String storageKey;
    private String status;
    private Integer createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String cleanupAuditOperationId;
}
