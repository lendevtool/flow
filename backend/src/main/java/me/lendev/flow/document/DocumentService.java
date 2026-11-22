package me.lendev.flow.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

/**
 * 文档中心的业务接口实现，协调权限、短事务与文件传输。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DocumentService implements DocumentCenter {

    private static final String INVALID_REQUEST = "error.document.request";

    private final DocumentRepository repository;
    private final DocumentDimensions dimensions;
    private final DocumentMutations mutations;
    private final DocumentTransfers transfers;
    private final SftpDocumentStorageProperties sftp;

    @Value("${spring.servlet.multipart.max-file-size:5MB}")
    private String maxFileSize;

    @Override
    public Version upload(BusinessRecord business, Long documentId, Metadata metadata, Upload upload) {
        return uploadAuthorized(DocumentAccess.business(business), documentId, metadata, upload, DocumentAccess.actor());
    }

    Version uploadAuthorized(BusinessRecord owner, Long documentId, Metadata metadata, Upload upload, int actor) {
        requireOutsideTransaction();
        var reservation = mutations.reserve(owner, documentId, metadata, validUpload(upload), actor);
        try {
            transfers.upload(reservation.getDocumentId(), reservation.getId(), reservation, owner, upload.content());
            return mutations.publish(reservation.getDocumentId(), reservation.getId(), owner, actor).result();
        } catch (RuntimeException failure) {
            try {
                mutations.markFailed(reservation.getDocumentId(), reservation.getId());
            } catch (RuntimeException markingFailure) {
                failure.addSuppressed(markingFailure);
                log.warn("文档上传失败且未能写入失败状态，清理任务将在超时后回收，请检查数据库并跟踪版本状态，documentId={} versionId={} actorId={} uploadErrorType={} stateErrorType={}",
                        reservation.getDocumentId(), reservation.getId(), actor, failure.getClass().getSimpleName(),
                        markingFailure.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page list(BusinessRecord business, Filter filter) {
        int actor = DocumentAccess.actor();
        var query = filter == null ? new Filter(null, null, null, null, null, null) : filter;
        int page = query.pageNo() == null ? 1 : query.pageNo();
        int size = query.pageSize() == null ? 20 : query.pageSize();
        if (page < 1 || size < 1 || size > 100) {
            throw BadRequestException.localized(INVALID_REQUEST);
        }
        var slots = new LinkedHashMap<Integer, Boolean>();
        var normalized = normalizeFilters(query, slots);
        var owner = DocumentAccess.business(business);
        var result = repository.list(new DocumentRepository.Search(owner, actor, normalized, slots), page, size);
        var latest = repository.latest(result.records(), owner, actor);
        var records = result.records().stream().map(row -> {
            var version = latest.get(row.getId());
            var view = version == null ? null : DocumentViews.version(version);
            String path = owner == null && version != null ? sftp.relativePath(version.getStorageKey()) : null;
            return DocumentViews.result(row, view, List.of(), path);
        }).toList();
        return new Page(records, result.total());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Document detail(BusinessRecord business, long documentId) {
        var owner = DocumentAccess.business(business);
        var row = DocumentAccess.requireScope(repository.get(documentId, false), owner, DocumentAccess.actor());
        return owner == null
                ? mutations.detail(row, version -> DocumentViews.version(version, sftp.relativePath(version.getStorageKey())))
                : mutations.detail(row);
    }

    @Override
    public Document update(BusinessRecord business, long documentId, Metadata metadata) {
        int actor = DocumentAccess.actor();
        var owner = DocumentAccess.business(business);
        if (AuditParticipants.active()) {
            return mutations.editInParent(documentId, owner, metadata, actor);
        }
        return mutations.edit(documentId, owner, metadata, actor).result();
    }

    @Override
    public Document bind(BusinessRecord business, long documentId, long versionId) {
        return bindAuthorized(DocumentAccess.requireBusiness(business), documentId, versionId, DocumentAccess.actor());
    }

    Document bindAuthorized(BusinessRecord owner, long documentId, long versionId, int actor) {
        if (AuditParticipants.active()) {
            return mutations.bindInParent(documentId, versionId, owner, actor);
        }
        return mutations.bind(documentId, versionId, owner, actor).result();
    }

    @Override
    public void detach(BusinessRecord business, long documentId) {
        int actor = DocumentAccess.actor();
        var owner = DocumentAccess.requireBusiness(business);
        if (AuditParticipants.active()) {
            mutations.detachInParent(documentId, owner, actor);
        } else {
            mutations.detach(documentId, owner, actor);
        }
    }

    @Override
    public void download(BusinessRecord business, long documentId, Long versionId, Download download) {
        downloadAuthorized(DocumentAccess.business(business), documentId, versionId, download, DocumentAccess.actor());
    }

    void downloadAuthorized(BusinessRecord owner, long documentId, Long versionId, Download download, int actor) {
        requireOutsideTransaction();
        Objects.requireNonNull(download, "需要提供下载回调");
        transfers.download(documentId, versionId, owner, () -> mutations.selectDownload(documentId, versionId, owner, actor),
                download);
    }

    List<Version> readyGenerationVersions(BusinessRecord owner, int actor) {
        var found = repository.list(new DocumentRepository.Search(owner, actor,
                new Filter(1, 3, null, null, null, Map.of()), Map.of()), 1, 3);
        return found.records().stream()
                .flatMap(row -> repository.readyVersions(row.getId(), 3).stream())
                .map(DocumentViews::version).toList();
    }

    List<DocumentGenerationFiles.Unadopted> unadoptedGenerationVersions(java.time.LocalDateTime before) {
        return repository.unadoptedGenerationVersions(before).stream().map(version -> {
            var document = repository.get(version.getDocumentId(), false);
            return new DocumentGenerationFiles.Unadopted(DocumentGenerationFiles.Target.valueOf(document.getBusinessType()),
                    Long.parseLong(document.getBusinessId()), version.getDocumentId(), version.getId());
        }).toList();
    }

    void discardGenerationVersion(BusinessRecord owner, long documentId, long versionId, int actor) {
        mutations.discardGenerationVersion(owner, documentId, versionId, actor);
    }

    @Override
    public void delete(long documentId) {
        int actor = DocumentAccess.actor();
        if (AuditParticipants.active()) {
            mutations.deleteInParent(documentId, actor);
        } else {
            mutations.delete(documentId, actor);
        }
    }

    private Upload validUpload(Upload request) {
        if (request == null || request.sizeBytes() < 0 || request.content() == null) {
            throw BadRequestException.localized("error.document.file");
        }
        if (request.sizeBytes() > DataSize.parse(maxFileSize).toBytes()) {
            throw DocumentFailure.tooLarge();
        }
        String type = request.contentType();
        try {
            if (type == null || type.length() > 128 || !MediaType.parseMediaType(type).isConcrete()) {
                type = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
        } catch (IllegalArgumentException invalidType) {
            type = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return new Upload(request.originalFileName(), type, request.sizeBytes(), request.content());
    }

    private Filter normalizeFilters(Filter query, Map<Integer, Boolean> textSlots) {
        String keyword = query.keyword() == null || query.keyword().isBlank() ? null
                : DocumentNames.required(query.keyword(), 255, INVALID_REQUEST);
        if (query.departmentId() != null && query.departmentId() <= 0
                || query.productId() != null && query.productId() <= 0) {
            throw BadRequestException.localized(INVALID_REQUEST);
        }
        return new Filter(query.pageNo(), query.pageSize(), keyword, query.departmentId(), query.productId(),
                normalizeDimensionFilters(query.dimensions(), textSlots));
    }

    private Map<Integer, String> normalizeDimensionFilters(Map<Integer, String> requested, Map<Integer, Boolean> textSlots) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        var definitions = dimensions.options().dimensions().stream().collect(
                Collectors.toMap(DocumentDimensions.Dimension::slot, Function.identity()));
        var values = new LinkedHashMap<Integer, String>();
        requested.forEach((slot, value) -> {
            var definition = slot == null ? null : definitions.get(slot);
            if (definition == null || definition.inputType() == null) {
                throw BadRequestException.localized(INVALID_REQUEST);
            }
            String normalized = DocumentNames.required(value, 255, INVALID_REQUEST);
            boolean text = "TEXT".equals(definition.inputType());
            textSlots.put(slot, text);
            values.put(slot, text ? normalized : optionId(normalized));
        });
        return Map.copyOf(values);
    }

    private static String optionId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return Long.toString(id);
            }
        } catch (NumberFormatException invalidId) {
            throw BadRequestException.localized(INVALID_REQUEST);
        }
        throw BadRequestException.localized(INVALID_REQUEST);
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive() || AuditParticipants.inProgress()) {
            throw new IllegalStateException("文档传输必须在业务数据库事务及外层审计之外执行");
        }
    }
}
