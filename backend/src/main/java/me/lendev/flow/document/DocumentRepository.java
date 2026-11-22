package me.lendev.flow.document;

import static com.mybatisflex.core.query.QueryMethods.exists;
import static me.lendev.flow.document.entity.table.DocumentRowTableDef.DOCUMENT_ROW;
import static me.lendev.flow.document.entity.table.DocumentVersionRowTableDef.DOCUMENT_VERSION_ROW;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.document.DocumentCenter.BusinessRecord;
import me.lendev.flow.document.DocumentCenter.Filter;
import me.lendev.flow.document.DocumentCenter.Metadata;
import me.lendev.flow.document.entity.DocumentRow;
import me.lendev.flow.document.entity.DocumentVersionRow;
import me.lendev.flow.document.mapper.DocumentMapper;
import me.lendev.flow.document.mapper.DocumentVersionMapper;
import org.springframework.stereotype.Repository;

/**
 * 持久化文档元数据、发布版本和清理状态，提供按业务归属与维度筛选的查询。
 */
@Repository
@RequiredArgsConstructor
class DocumentRepository {

    private static final List<QueryColumn> DIMENSION_COLUMNS = List.of(
            DOCUMENT_ROW.DIMENSION1_VALUE, DOCUMENT_ROW.DIMENSION2_VALUE, DOCUMENT_ROW.DIMENSION3_VALUE,
            DOCUMENT_ROW.DIMENSION4_VALUE, DOCUMENT_ROW.DIMENSION5_VALUE, DOCUMENT_ROW.DIMENSION6_VALUE);

    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;

    enum Status {UPLOADING, READY, FAILED, DELETE_PENDING, DELETED}

    record Search(BusinessRecord business, int actor, Filter filter, Map<Integer, Boolean> textSlots) {

    }

    record Rows(List<DocumentRow> records, long total) {

    }

    DocumentRow get(long id, boolean lock) {
        var query = QueryWrapper.create().where(DOCUMENT_ROW.ID.eq(id));
        return documents.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    Rows list(Search search, int page, int size) {
        QueryWrapper query = listQuery(search);
        long total = documents.selectCountByQuery(query);
        if (total == 0) {
            return new Rows(List.of(), 0);
        }
        var rows = documents.selectListByQuery(query.orderBy(DOCUMENT_ROW.ID.desc())
                .limit((long) (page - 1) * size, size));
        return new Rows(rows, total);
    }

    private QueryWrapper listQuery(Search search) {
        var business = search.business();
        var query = QueryWrapper.create().where(DOCUMENT_ROW.DELETED_AT.isNull())
                .and(exists(QueryWrapper.create().select(DOCUMENT_VERSION_ROW.ID).from(DOCUMENT_VERSION_ROW)
                        .where(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(DOCUMENT_ROW.ID)
                                .and(DOCUMENT_VERSION_ROW.STATUS.eq(Status.READY.name())))));
        if (business == null) {
            query.and(DOCUMENT_ROW.SOURCE_MODULE.isNull()).and(DOCUMENT_ROW.CREATED_BY.eq(search.actor()));
        } else {
            query.and(DOCUMENT_ROW.SOURCE_MODULE.eq(business.module()))
                    .and(DOCUMENT_ROW.BUSINESS_TYPE.eq(business.type())).and(DOCUMENT_ROW.BUSINESS_ID.eq(business.id()));
        }
        var filter = search.filter();
        if (filter.departmentId() != null) {
            query.and(DOCUMENT_ROW.DEPARTMENT_ID.eq(filter.departmentId()));
        }
        if (filter.productId() != null) {
            query.and(DOCUMENT_ROW.PRODUCT_ID.eq(filter.productId()));
        }
        if (filter.keyword() != null) {
            query.and("doc_document.name LIKE ? ESCAPE '!'", contains(filter.keyword()));
        }
        filter.dimensions().forEach((slot, value) -> {
            var column = DIMENSION_COLUMNS.get(slot - 1);
            if (Boolean.TRUE.equals(search.textSlots().get(slot))) {
                // 槽位号由服务验证。查询值始终使用绑定参数。
                query.and("doc_document.dimension" + slot + "Value LIKE ? ESCAPE '!'", contains(value));
            } else {
                query.and(column.eq(value));
            }
        });
        return query;
    }

    private static String contains(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    DocumentRow create(BusinessRecord business, Metadata metadata, int actor, LocalDateTime now) {
        var row = new DocumentRow();
        row.setName(metadata.name());
        row.setDepartmentId(metadata.departmentId());
        row.setProductId(metadata.productId());
        row.setDimension1Value(metadata.dimensions().get(1));
        row.setDimension2Value(metadata.dimensions().get(2));
        row.setDimension3Value(metadata.dimensions().get(3));
        row.setDimension4Value(metadata.dimensions().get(4));
        row.setDimension5Value(metadata.dimensions().get(5));
        row.setDimension6Value(metadata.dimensions().get(6));
        if (business != null) {
            row.setSourceModule(business.module());
            row.setBusinessType(business.type());
            row.setBusinessId(business.id());
        }
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        documents.insertSelective(row);
        return row;
    }

    void edit(long id, Metadata metadata, int actor, LocalDateTime now) {
        var update = documentUpdate(id, actor, now)
                .set(DOCUMENT_ROW.NAME, metadata.name())
                .set(DOCUMENT_ROW.DEPARTMENT_ID, metadata.departmentId())
                .set(DOCUMENT_ROW.PRODUCT_ID, metadata.productId());
        for (int slot = 1; slot <= DIMENSION_COLUMNS.size(); slot++) {
            update.set(DIMENSION_COLUMNS.get(slot - 1), metadata.dimensions().get(slot));
        }
        update.update();
    }

    void setBinding(long id, BusinessRecord business, Long versionId, int actor, LocalDateTime now) {
        documentUpdate(id, actor, now)
                .set(DOCUMENT_ROW.SOURCE_MODULE, business == null ? null : business.module())
                .set(DOCUMENT_ROW.BUSINESS_TYPE, business == null ? null : business.type())
                .set(DOCUMENT_ROW.BUSINESS_ID, business == null ? null : business.id())
                .set(DOCUMENT_ROW.BUSINESS_VERSION_ID, versionId).update();
    }

    void touch(long id, int actor, LocalDateTime now) {
        documentUpdate(id, actor, now).update();
    }

    void markDeleted(long id, int actor, LocalDateTime now) {
        documentUpdate(id, actor, now).set(DOCUMENT_ROW.DELETED_AT, now).update();
    }

    private UpdateChain<DocumentRow> documentUpdate(long id, int actor, LocalDateTime now) {
        return UpdateChain.of(documents).set(DOCUMENT_ROW.UPDATED_BY, actor).set(DOCUMENT_ROW.UPDATED_AT, now)
                .where(DOCUMENT_ROW.ID.eq(id));
    }

    DocumentVersionRow reserveVersion(long documentId, String originalName, String contentType, long size, String key,
                                      int actor, LocalDateTime now) {
        var version = new DocumentVersionRow();
        version.setDocumentId(documentId);
        version.setOriginalFileName(originalName);
        version.setContentType(contentType);
        version.setSizeBytes(size);
        version.setStorageKey(key);
        version.setStatus(Status.UPLOADING.name());
        version.setCreatedBy(actor);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        versions.insertSelective(version);
        return version;
    }

    DocumentVersionRow version(long documentId, long versionId) {
        return versions.selectOneByQuery(QueryWrapper.create()
                .where(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(documentId).and(DOCUMENT_VERSION_ROW.ID.eq(versionId))));
    }

    List<DocumentVersionRow> unadoptedGenerationVersions(LocalDateTime before) {
        return versions.unadoptedGenerationVersions(before);
    }

    List<DocumentVersionRow> readyVersions(long documentId, Integer limit) {
        var query = QueryWrapper.create().where(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(documentId)
                        .and(DOCUMENT_VERSION_ROW.STATUS.eq(Status.READY.name())))
                .orderBy(DOCUMENT_VERSION_ROW.VERSION_DATE.desc(), DOCUMENT_VERSION_ROW.DAILY_SEQUENCE.desc());
        return versions.selectListByQuery(limit == null ? query : query.limit(limit));
    }

    Map<Long, DocumentVersionRow> latest(List<DocumentRow> rows, BusinessRecord business, int actor) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        var ids = rows.stream().map(DocumentRow::getId).toList();
        return versions.latestByDocuments(ids, actor, business == null ? null : business.module(),
                        business == null ? null : business.type(), business == null ? null : business.id()).stream()
                .collect(Collectors.toMap(DocumentVersionRow::getDocumentId, Function.identity()));
    }

    int nextSequence(long documentId, LocalDate date) {
        var last = versions.selectOneByQuery(QueryWrapper.create()
                .where(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(documentId).and(DOCUMENT_VERSION_ROW.VERSION_DATE.eq(date)))
                .orderBy(DOCUMENT_VERSION_ROW.DAILY_SEQUENCE.desc()).limit(1).forUpdate());
        return last == null ? 1 : Math.incrementExact(last.getDailySequence());
    }

    void publish(long id, LocalDate date, int sequence, LocalDateTime now) {
        versionUpdate(id, Status.READY, now).set(DOCUMENT_VERSION_ROW.VERSION_DATE, date)
                .set(DOCUMENT_VERSION_ROW.DAILY_SEQUENCE, sequence).update();
    }

    void status(long id, Status status, LocalDateTime now) {
        versionUpdate(id, status, now).update();
    }

    private UpdateChain<DocumentVersionRow> versionUpdate(long id, Status status, LocalDateTime now) {
        return UpdateChain.of(versions).set(DOCUMENT_VERSION_ROW.STATUS, status.name())
                .set(DOCUMENT_VERSION_ROW.UPDATED_AT, now).where(DOCUMENT_VERSION_ROW.ID.eq(id));
    }

    boolean hasUploadInProgress(long id) {
        return versions.selectCountByQuery(QueryWrapper.create().where(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(id)
                .and(DOCUMENT_VERSION_ROW.STATUS.eq(Status.UPLOADING.name())))) > 0;
    }

    void queueCleanup(long id, String operationId, LocalDateTime now) {
        UpdateChain.of(versions).set(DOCUMENT_VERSION_ROW.STATUS, Status.DELETE_PENDING.name())
                .set(DOCUMENT_VERSION_ROW.CLEANUP_AUDIT_OPERATION_ID, operationId)
                .set(DOCUMENT_VERSION_ROW.UPDATED_AT, now)
                .where(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(id)
                        .and(DOCUMENT_VERSION_ROW.STATUS.in(Status.READY.name(), Status.FAILED.name()))).update();
    }

    List<DocumentVersionRow> cleanupCandidates(LocalDateTime retryBefore, LocalDateTime staleBefore) {
        return versions.selectListByQuery(QueryWrapper.create()
                .where("(status IN ('FAILED','DELETE_PENDING') AND updatedAt <= ?) "
                        + "OR (status = 'UPLOADING' AND updatedAt <= ?)", retryBefore, staleBefore)
                .orderBy(DOCUMENT_VERSION_ROW.UPDATED_AT.asc(), DOCUMENT_VERSION_ROW.ID.asc()).limit(25));
    }

    static Map<Integer, String> dimensions(DocumentRow row) {
        String[] values = {row.getDimension1Value(), row.getDimension2Value(), row.getDimension3Value(),
                row.getDimension4Value(), row.getDimension5Value(), row.getDimension6Value()};
        var result = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                result.put(i + 1, values[i]);
            }
        }
        return Map.copyOf(result);
    }
}
