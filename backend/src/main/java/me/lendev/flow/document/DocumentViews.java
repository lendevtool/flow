package me.lendev.flow.document;

import static me.lendev.flow.document.DocumentCenter.*;

import java.time.ZoneOffset;
import java.util.List;

import me.lendev.flow.document.entity.DocumentRow;
import me.lendev.flow.document.entity.DocumentVersionRow;

/**
 * 将文档和已发布版本映射为业务视图，按 UTC 解释数据库时间并生成下载文件名。
 */
final class DocumentViews {

    private DocumentViews() {
    }

    static Document result(DocumentRow row, Version latest, List<Version> versions) {
        return result(row, latest, versions, null);
    }

    static Document result(DocumentRow row, Version latest, List<Version> versions, String sftpPath) {
        return new Document(row.getId(), row.getName(), row.getDepartmentId(), row.getProductId(),
                DocumentRepository.dimensions(row), latest, versions, row.getBusinessVersionId(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getUpdatedAt().toInstant(ZoneOffset.UTC), sftpPath);
    }

    static Version version(DocumentVersionRow row) {
        return version(row, null);
    }

    static Version version(DocumentVersionRow row, String sftpPath) {
        String number = DocumentNames.versionNo(row.getVersionDate(), row.getDailySequence());
        return new Version(row.getId(), row.getDocumentId(), number, row.getOriginalFileName(),
                DocumentNames.downloadName(row.getOriginalFileName(), number), row.getContentType(), row.getSizeBytes(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC), sftpPath);
    }
}
