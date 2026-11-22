package me.lendev.flow.document;

import static me.lendev.flow.audit.AuditOperation.*;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentAuditDetails.Transfer;
import me.lendev.flow.document.DocumentCenter.BusinessRecord;
import me.lendev.flow.document.DocumentCenter.Download;
import me.lendev.flow.document.entity.DocumentVersionRow;
import org.springframework.stereotype.Service;

/**
 * 在数据库事务之外执行 SFTP 读写，并记录外部操作的结果。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DocumentTransfers {

    private final DocumentStorage storage;
    private final DocumentAuditDetails audit;

    @Audited(DOCUMENT_UPLOAD)
    public Transfer upload(long documentId, long versionId, DocumentVersionRow row, BusinessRecord business, InputStream content) {
        var measured = new UploadStream(content, row.getSizeBytes());
        try {
            storage.put(row.getStorageKey(), measured);
        } catch (IOException failure) {
            logStorageFailure("上传", row, null, failure);
            throw DocumentFailure.uploadUnavailable(failure);
        }
        measured.requireSize();
        log.info("文档文件已上传到存储，requestId={} documentId={} versionId={} sizeBytes={}",
                AuditParticipants.currentRequestId(), documentId, versionId, row.getSizeBytes());
        return audit.uploaded(row, business);
    }

    @Audited(DOCUMENT_DOWNLOAD)
    public Transfer download(long documentId, Long versionId, BusinessRecord business,
                             Supplier<DocumentVersionRow> lookup, Download download) {
        var row = lookup.get();
        var version = DocumentViews.version(row);
        try {
            storage.read(row.getStorageKey(), stream -> download.write(version, stream));
        } catch (DownloadOutputException failure) {
            throw DocumentFailure.storage(failure);
        } catch (IOException failure) {
            logStorageFailure("下载", row, null, failure);
            throw DocumentFailure.storage(failure);
        }
        log.info("文档文件传输完成，requestId={} documentId={} versionId={} sizeBytes={}",
                AuditParticipants.currentRequestId(), documentId, row.getId(), row.getSizeBytes());
        return audit.transferred(row, business, "完成文档文件传输");
    }

    @Audited(DOCUMENT_CLEANUP)
    public Transfer cleanup(long documentId, long versionId, String operationId, DocumentVersionRow row) {
        try {
            storage.delete(row.getStorageKey());
        } catch (IOException failure) {
            logStorageFailure("清理", row, operationId, failure);
            throw DocumentFailure.storage(failure);
        }
        log.info("文档存储清理完成，operationId={} documentId={} versionId={}", operationId, documentId, versionId);
        return audit.transferred(row, null, "完成 SFTP 文件清理");
    }

    private static void logStorageFailure(String operation, DocumentVersionRow row, String operationId,
                                          IOException failure) {
        log.warn("文档存储{}失败，requestId={} operationId={} documentId={} versionId={} sizeBytes={} errorType={} causeType={}，请检查 SFTP 连通性、主机密钥及目录读写权限",
                operation, AuditParticipants.currentRequestId(), operationId, row.getDocumentId(), row.getId(),
                row.getSizeBytes(), failure.getClass().getSimpleName(),
                failure.getCause() == null ? null : failure.getCause().getClass().getSimpleName());
    }

    /**
     * 流式计数以限制上传大小，读取结束后须调用 {@link #requireSize()} 检查提前结束的流。
     */
    private static final class UploadStream extends FilterInputStream {

        private final long expectedSize;
        private long received;

        private UploadStream(InputStream content, long expectedSize) {
            super(content);
            this.expectedSize = expectedSize;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                received++;
                checkBound();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, expectedSize - received + 1);
            int count = in.read(buffer, offset, allowed);
            if (count > 0) {
                received += count;
                checkBound();
            }
            return count;
        }

        private void checkBound() {
            if (received > expectedSize) {
                throw BadRequestException.localized("error.document.file");
            }
        }

        private void requireSize() {
            if (received != expectedSize) {
                throw BadRequestException.localized("error.document.file");
            }
        }
    }
}
