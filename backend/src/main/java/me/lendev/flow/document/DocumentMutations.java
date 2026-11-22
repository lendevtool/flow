package me.lendev.flow.document;

import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.document.DocumentCenter.*;
import static me.lendev.flow.document.DocumentRepository.Status.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentAuditDetails.Change;
import me.lendev.flow.document.entity.DocumentRow;
import me.lendev.flow.document.entity.DocumentVersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档写入的短事务、发布序号和业务记录绑定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DocumentMutations {

    private static final ZoneId VERSION_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter STORAGE_MONTH = DateTimeFormatter.ofPattern("uuuuMM", Locale.ROOT)
            .withZone(VERSION_ZONE);

    private final DocumentRepository repository;
    private final DocumentDimensions dimensions;
    private final DocumentAuditDetails audit;
    private final Clock clock;

    /**
     * 在短事务内预留上传版本，文件字节由调用方在事务结束后传输。
     *
     * @param business 所属业务记录，未绑定文档传 {@code null}
     * @param id       要追加版本的文档 ID，为 {@code null} 时创建文档
     * @param metadata 新建文档时使用的元数据
     * @param upload   已校验的上传描述
     * @param actor    操作用户 ID
     * @return 处于上传状态的版本记录
     */
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersionRow reserve(BusinessRecord business, Long id, Metadata metadata, Upload upload, int actor) {
        var uploadedAt = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(uploadedAt, ZoneOffset.UTC);
        String fileName = DocumentNames.originalName(upload.originalFileName());
        var row = id == null
                ? repository.create(business, validated(metadata, null), actor, now)
                : DocumentAccess.requireScope(repository.get(id, true), business, actor);
        String storageKey = STORAGE_MONTH.format(uploadedAt) + "/" + actor + "/" + UUID.randomUUID();
        return repository.reserveVersion(row.getId(), fileName, upload.contentType(), upload.sizeBytes(),
                storageKey, actor, now);
    }

    /**
     * 在文档行锁下发布上传中的版本，按上海时区的业务日期分配发布序号。
     *
     * @param documentId 文档 ID
     * @param versionId  已完成文件传输的预留版本 ID
     * @param business   调用方有权操作的业务记录范围
     * @param actor      操作用户 ID
     * @return 已发布版本及其审计变更
     */
    @Audited(DOCUMENT_PUBLISH)
    @Transactional(rollbackFor = Exception.class)
    public Change<Version> publish(long documentId, long versionId, BusinessRecord business, int actor) {
        var document = DocumentAccess.requireScope(repository.get(documentId, true), business, actor);
        var version = repository.version(documentId, versionId);
        if (version == null || !UPLOADING.name().equals(version.getStatus())) {
            throw DocumentFailure.conflict("error.document.busy");
        }
        var details = new ArrayList<AuditChange>(2);
        boolean first = repository.readyVersions(documentId, 1).isEmpty();
        var now = clock.instant();
        LocalDate date = now.atZone(VERSION_ZONE).toLocalDate();
        int sequence = repository.nextSequence(documentId, date);
        var updatedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        repository.publish(versionId, date, sequence, updatedAt);
        repository.touch(documentId, actor, updatedAt);
        var published = repository.version(documentId, versionId);
        if (first) {
            details.add(audit.document(null, document));
        }
        details.add(audit.published(published, business));
        log.info("文档版本发布状态已写入，requestId={} documentId={} versionId={} firstVersion={}",
                AuditParticipants.currentRequestId(), documentId, versionId, first);
        return new Change<>(DocumentViews.version(published), List.copyOf(details));
    }

    /**
     * 标记失败的上传。清理任务若已领取或删除文件，则重新挂入删除队列以覆盖迟到的写入。
     *
     * @param documentId 文档 ID
     * @param versionId  上传失败的版本 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(long documentId, long versionId) {
        repository.get(documentId, true);
        var row = repository.version(documentId, versionId);
        if (row == null) {
            return;
        }
        if (UPLOADING.name().equals(row.getStatus())) {
            repository.status(versionId, FAILED, now());
            log.info("文档上传已标记失败，documentId={} versionId={}", documentId, versionId);
        } else if (DELETE_PENDING.name().equals(row.getStatus()) || DELETED.name().equals(row.getStatus())) {
            repository.status(versionId, DELETE_PENDING, now());
            log.info("文档上传失败后重新登记清理，documentId={} versionId={}", documentId, versionId);
        }
    }

    @Audited(DOCUMENT_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public Change<Document> edit(long id, BusinessRecord business, Metadata metadata, int actor) {
        return editChange(id, business, metadata, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Document editInParent(long id, BusinessRecord business, Metadata metadata, int actor) {
        return participate(editChange(id, business, metadata, actor));
    }

    private Change<Document> editChange(long id, BusinessRecord business, Metadata metadata, int actor) {
        dimensions.lockConfiguration();
        var before = DocumentAccess.requireScope(repository.get(id, true), business, actor);
        var beforeView = detail(before);
        var current = new Metadata(beforeView.name(), beforeView.departmentId(), beforeView.productId(),
                beforeView.dimensions());
        var next = validated(metadata, before);
        if (current.equals(next)) {
            return new Change<>(beforeView, List.of());
        }
        repository.edit(id, next, actor, now());
        var after = repository.get(id, false);
        return new Change<>(DocumentViews.result(after, beforeView.latestVersion(), beforeView.versions()),
                List.of(audit.document(before, after)));
    }

    @Audited(DOCUMENT_BIND)
    @Transactional(rollbackFor = Exception.class)
    public Change<Document> bind(long id, long versionId, BusinessRecord business, int actor) {
        return bindChange(id, versionId, business, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Document bindInParent(long id, long versionId, BusinessRecord business, int actor) {
        return participate(bindChange(id, versionId, business, actor));
    }

    private Change<Document> bindChange(long id, long versionId, BusinessRecord business, int actor) {
        var before = repository.get(id, true);
        DocumentAccess.checkBind(before, business, actor);
        var version = repository.version(id, versionId);
        if (version == null || !READY.name().equals(version.getStatus())) {
            throw DocumentFailure.conflict("error.document.business-version");
        }
        if (business.equals(DocumentAccess.business(before)) && Objects.equals(before.getBusinessVersionId(), versionId)) {
            return new Change<>(detail(before), List.of());
        }
        repository.setBinding(id, business, versionId, actor, now());
        var after = repository.get(id, false);
        log.info("文档业务版本绑定已写入，requestId={} documentId={} versionId={}",
                AuditParticipants.currentRequestId(), id, versionId);
        return new Change<>(detail(after), List.of(audit.document(before, after)));
    }

    @Audited(DOCUMENT_DETACH)
    @Transactional(rollbackFor = Exception.class)
    public Change<Void> detach(long id, BusinessRecord business, int actor) {
        return detachChange(id, business, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void detachInParent(long id, BusinessRecord business, int actor) {
        participate(detachChange(id, business, actor));
    }

    private Change<Void> detachChange(long id, BusinessRecord business, int actor) {
        var before = DocumentAccess.requireScope(repository.get(id, true), business, actor);
        repository.setBinding(id, null, null, actor, now());
        return new Change<>(null, List.of(audit.document(before, repository.get(id, false))));
    }

    @Audited(DOCUMENT_DELETE)
    @Transactional(rollbackFor = Exception.class)
    public Change<Void> delete(long id, int actor) {
        return deleteChange(id, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void deleteInParent(long id, int actor) {
        participate(deleteChange(id, actor));
    }

    private Change<Void> deleteChange(long id, int actor) {
        var before = DocumentAccess.requireScope(repository.get(id, true), null, actor);
        if (repository.hasUploadInProgress(id)) {
            throw DocumentFailure.conflict("error.document.busy");
        }
        var now = now();
        repository.markDeleted(id, actor, now);
        repository.queueCleanup(id, AuditParticipants.operationId(), now);
        log.info("文档删除及文件清理已登记，requestId={} documentId={}", AuditParticipants.currentRequestId(), id);
        return new Change<>(null, List.of(audit.document(before, repository.get(id, false))));
    }

    /**
     * 清理指定业务范围中未被锁定的生成文件版本，后台按原存储清理队列删除字节。
     */
    @Transactional(rollbackFor = Exception.class)
    public void discardGenerationVersion(BusinessRecord owner, long documentId, long versionId, int actor) {
        var candidate = repository.get(documentId, true);
        if (candidate == null || candidate.getDeletedAt() != null) return;
        var document = DocumentAccess.requireScope(candidate, owner, actor);
        if (Objects.equals(document.getBusinessVersionId(), versionId)) return;
        var version = repository.version(documentId, versionId);
        if (version != null && READY.name().equals(version.getStatus())) {
            var time = now();
            repository.status(versionId, DELETE_PENDING, time);
            if (document.getBusinessVersionId() == null && repository.readyVersions(documentId, 1).isEmpty()
                    && !repository.hasUploadInProgress(documentId)) {
                repository.markDeleted(documentId, actor, time);
            }
        }
    }

    /**
     * 短事务内校验归属并选定版本，提交后才打开 SFTP 流。
     *
     * @param documentId 文档 ID
     * @param versionId  指定的版本 ID，独立文档为 {@code null} 时取最新发布版本
     * @param business   业务记录范围，绑定文档只允许读取绑定版本
     * @param actor      操作用户 ID
     * @return 可下载的已发布版本
     */
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersionRow selectDownload(long documentId, Long versionId, BusinessRecord business, int actor) {
        var row = DocumentAccess.requireScope(repository.get(documentId, true), business, actor);
        Long selected = versionId;
        if (business != null) {
            selected = row.getBusinessVersionId();
            if (selected == null || versionId != null && !versionId.equals(selected)) {
                throw DocumentFailure.conflict("error.document.business-version");
            }
        }
        var version = selected == null ? repository.readyVersions(documentId, 1).stream().findFirst().orElse(null)
                : repository.version(documentId, selected);
        if (version == null || !READY.name().equals(version.getStatus())) {
            throw DocumentFailure.missing();
        }
        return version;
    }

    Document detail(DocumentRow row) {
        return detail(row, DocumentViews::version);
    }

    Document detail(DocumentRow row, Function<DocumentVersionRow, Version> versionView) {
        var versions = repository.readyVersions(row.getId(), null).stream().map(versionView).toList();
        if (versions.isEmpty()) {
            throw DocumentFailure.missing();
        }
        return DocumentViews.result(row, versions.getFirst(), versions);
    }

    /**
     * 为失败上传、超时上传或待删除版本获取清理租约，其他工作线程须等待重试窗口。
     *
     * @param documentId  文档 ID
     * @param versionId   待清理版本 ID
     * @param retryBefore 失败或待删除记录可重新领取的时间上界
     * @param staleBefore 上传记录判定为超时的时间上界
     * @return 携带租约时间的版本，状态已变化或尚未到期时返回 {@code null}
     */
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersionRow claimCleanup(long documentId, long versionId, LocalDateTime retryBefore,
                                           LocalDateTime staleBefore) {
        repository.get(documentId, true);
        var row = repository.version(documentId, versionId);
        if (row == null) {
            return null;
        }
        boolean stale = UPLOADING.name().equals(row.getStatus()) && !row.getUpdatedAt().isAfter(staleBefore);
        boolean pending = (FAILED.name().equals(row.getStatus()) || DELETE_PENDING.name().equals(row.getStatus()))
                && !row.getUpdatedAt().isAfter(retryBefore);
        if (!stale && !pending) {
            return null;
        }
        var lease = now();
        repository.status(versionId, DELETE_PENDING, lease);
        row.setUpdatedAt(lease);
        return row;
    }

    /**
     * 确认文件已删除。只有仍持有当前租约的调用才能推进到已删除状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleaned(long documentId, long versionId, LocalDateTime lease) {
        repository.get(documentId, true);
        var row = repository.version(documentId, versionId);
        if (row != null && DELETE_PENDING.name().equals(row.getStatus()) && lease.equals(row.getUpdatedAt())) {
            repository.status(versionId, DELETED, now());
        }
    }

    private Metadata validated(Metadata request, DocumentRow before) {
        if (request == null) {
            throw BadRequestException.localized("error.document.name");
        }
        String name = DocumentNames.required(request.name(), 255, "error.document.name");
        var values = dimensions.validate(request.departmentId(), request.productId(), request.dimensions(),
                before == null ? null : before.getDepartmentId(), before == null ? null : before.getProductId(),
                before == null ? null : DocumentRepository.dimensions(before));
        return new Metadata(name, request.departmentId(), request.productId(), values);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static <T> T participate(Change<T> change) {
        AuditParticipants.addChanges(change.details());
        return change.result();
    }
}
