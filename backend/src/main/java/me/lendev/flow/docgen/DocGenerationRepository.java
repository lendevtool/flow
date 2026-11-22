package me.lendev.flow.docgen;

import static me.lendev.flow.docgen.DocGenerationModels.*;
import static me.lendev.flow.docgen.entity.table.DocGenRecordTableDef.DOC_GEN_RECORD;
import static me.lendev.flow.docgen.entity.table.DocGenTemplateTableDef.DOC_GEN_TEMPLATE;
import static me.lendev.flow.docgen.entity.table.DocGenVersionTableDef.DOC_GEN_VERSION;
import static me.lendev.flow.document.DocumentGenerationFiles.SOURCE_MODULE;
import static me.lendev.flow.document.DocumentGenerationFiles.Target.GENERATION;
import static me.lendev.flow.document.entity.table.DocumentRowTableDef.DOCUMENT_ROW;
import static me.lendev.flow.document.entity.table.DocumentVersionRowTableDef.DOCUMENT_VERSION_ROW;

import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.docgen.entity.DocGenRecord;
import me.lendev.flow.docgen.entity.DocGenTemplate;
import me.lendev.flow.docgen.entity.DocGenVersion;
import me.lendev.flow.docgen.mapper.DocGenRecordMapper;
import me.lendev.flow.docgen.mapper.DocGenTemplateMapper;
import me.lendev.flow.docgen.mapper.DocGenVersionMapper;
import org.springframework.stereotype.Repository;

/**
 * 文档生成业务记录的 MyBatis-Flex 持久化与行锁查询。
 */
@Repository
@RequiredArgsConstructor
class DocGenerationRepository {
    private final DocGenTemplateMapper templates;
    private final DocGenVersionMapper versions;
    private final DocGenRecordMapper records;

    DocGenTemplate template(long id, boolean lock) {
        var query = QueryWrapper.create().where(DOC_GEN_TEMPLATE.ID.eq(id));
        return templates.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    DocGenTemplate templateByCode(String code) {
        return templates.selectOneByQuery(QueryWrapper.create().where(DOC_GEN_TEMPLATE.CODE.eq(code)));
    }

    List<DocGenTemplate> availableTemplates() {
        return templates.selectListByQuery(QueryWrapper.create()
                .where(DOC_GEN_TEMPLATE.ENABLED.eq(true)).and(DOC_GEN_TEMPLATE.CURRENT_VERSION_ID.isNotNull())
                .orderBy(DOC_GEN_TEMPLATE.ID.asc()));
    }

    Slice<DocGenTemplate> templates(TemplateQuery filter) {
        var query = QueryWrapper.create();
        if (filter.enabled() != null) query.and(DOC_GEN_TEMPLATE.ENABLED.eq(filter.enabled()));
        Boolean published = filter.published();
        if (published != null) {
            query.and(published.booleanValue() ? DOC_GEN_TEMPLATE.CURRENT_VERSION_ID.isNotNull() : DOC_GEN_TEMPLATE.CURRENT_VERSION_ID.isNull());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String term = contains(filter.keyword().strip());
            query.and("(name LIKE ? ESCAPE '!' OR code LIKE ? ESCAPE '!')", term, term);
        }
        long total = templates.selectCountByQuery(query);
        return new Slice<>(templates.selectListByQuery(query.orderBy(DOC_GEN_TEMPLATE.ID.desc())
                .limit(offset(filter.pageNo(), filter.pageSize()), filter.pageSize())), total);
    }

    void insert(DocGenTemplate row) {
        templates.insert(row);
    }

    void save(DocGenTemplate row) {
        templates.update(row, false);
    }

    DocGenVersion version(long id, boolean lock) {
        var query = QueryWrapper.create().where(DOC_GEN_VERSION.ID.eq(id));
        return versions.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    List<DocGenVersion> versions(long templateId) {
        return versions.selectListByQuery(versionScope(templateId).orderBy(DOC_GEN_VERSION.VERSION_NO.desc()));
    }

    DocGenVersion latest(long templateId) {
        return versions.selectOneByQuery(versionScope(templateId).orderBy(DOC_GEN_VERSION.VERSION_NO.desc()).limit(1));
    }

    void insert(DocGenVersion row) {
        versions.insert(row);
    }

    void save(DocGenVersion row) {
        versions.update(row, false);
    }

    DocGenRecord generation(long id, boolean lock) {
        var query = QueryWrapper.create().where(DOC_GEN_RECORD.ID.eq(id));
        return records.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    DocGenRecord byRequest(Actor actor, String key) {
        return records.selectOneByQuery(owner(actor).and(DOC_GEN_RECORD.REQUEST_KEY.eq(key)));
    }

    void insert(DocGenRecord row) {
        records.insert(row);
    }

    void save(DocGenRecord row) {
        records.update(row, false);
    }

    Slice<DocGenRecord> history(Actor actor, GenerationQuery filter, LocalDateTime from, LocalDateTime to) {
        var query = owner(actor);
        if (filter.templateId() != null) {
            query.and(DOC_GEN_RECORD.TEMPLATE_VERSION_ID.in(versionScope(filter.templateId()).select(DOC_GEN_VERSION.ID)
                    .from(DOC_GEN_VERSION)));
        }
        if (filter.status() != null) query.and(DOC_GEN_RECORD.STATUS.eq(filter.status()));
        if (from != null) query.and(DOC_GEN_RECORD.CREATED_AT.ge(from));
        if (to != null) query.and(DOC_GEN_RECORD.CREATED_AT.lt(to));
        long total = records.selectCountByQuery(query);
        var list = records.selectListByQuery(query.orderBy(DOC_GEN_RECORD.CREATED_AT.desc(), DOC_GEN_RECORD.ID.desc())
                .limit(offset(filter.pageNo(), filter.pageSize()), filter.pageSize()));
        return new Slice<>(list, total);
    }

    Slice<FileRow> files(int actor, FileQuery filter) {
        boolean generated = filter.type() == GENERATION;
        var fileId = generated ? DOC_GEN_RECORD.OUTPUT_VERSION_ID : DOC_GEN_VERSION.DOCUMENT_VERSION_ID;
        var documentId = generated ? DOC_GEN_RECORD.OUTPUT_DOCUMENT_ID : DOC_GEN_VERSION.DOCUMENT_ID;
        var query = QueryWrapper.create()
                .select(DOC_GEN_TEMPLATE.ID.as("templateId"), DOC_GEN_TEMPLATE.NAME.as("templateName"),
                        DOC_GEN_VERSION.ID.as("templateVersionId"), DOC_GEN_VERSION.VERSION_NO,
                        generated ? DOC_GEN_RECORD.STATUS : DOC_GEN_VERSION.STATUS,
                        DOCUMENT_VERSION_ROW.ORIGINAL_FILE_NAME, DOCUMENT_VERSION_ROW.SIZE_BYTES, DOCUMENT_VERSION_ROW.CREATED_AT,
                        DOCUMENT_VERSION_ROW.STORAGE_KEY)
                .from(DOC_GEN_VERSION)
                .innerJoin(DOC_GEN_TEMPLATE).on(DOC_GEN_VERSION.TEMPLATE_ID.eq(DOC_GEN_TEMPLATE.ID));
        if (generated) {
            query.select(DOC_GEN_RECORD.ID.as("generationId"))
                    .innerJoin(DOC_GEN_RECORD).on(DOC_GEN_RECORD.TEMPLATE_VERSION_ID.eq(DOC_GEN_VERSION.ID));
        }
        query.innerJoin(DOCUMENT_VERSION_ROW).on(DOCUMENT_VERSION_ROW.ID.eq(fileId)
                        .and(DOCUMENT_VERSION_ROW.DOCUMENT_ID.eq(documentId)))
                .innerJoin(DOCUMENT_ROW).on(DOCUMENT_ROW.ID.eq(documentId)
                        .and(DOCUMENT_ROW.BUSINESS_VERSION_ID.eq(fileId)))
                .where(DOCUMENT_ROW.DELETED_AT.isNull()).and(DOCUMENT_VERSION_ROW.STATUS.eq("READY"))
                .and(DOCUMENT_ROW.SOURCE_MODULE.eq(SOURCE_MODULE)).and(DOCUMENT_ROW.BUSINESS_TYPE.eq(filter.type().name()));
        if (generated) query.and(DOC_GEN_RECORD.CREATED_BY.eq(actor)).and(DOC_GEN_RECORD.STATUS.eq("SUCCEEDED"));
        if (filter.keyword() != null) {
            var term = contains(filter.keyword());
            query.and("""
                    (biz_doc_template.name LIKE ? ESCAPE '!' OR biz_doc_template.code LIKE ? ESCAPE '!'
                     OR doc_document_version.originalFileName LIKE ? ESCAPE '!')
                    """, term, term, term);
        }
        var page = versions.paginateAs(filter.pageNo(), filter.pageSize(),
                query.orderBy(DOCUMENT_VERSION_ROW.ID.desc()), FileRow.class);
        return new Slice<>(page.getRecords(), page.getTotalRow());
    }

    @Data
    static class FileRow {
        private Long templateId;
        private Long templateVersionId;
        private String templateName;
        private int versionNo;
        private Long generationId;
        private String originalFileName;
        private long sizeBytes;
        private LocalDateTime createdAt;
        private String status;
        private String storageKey;
    }

    void clearInput(long id) {
        records.clearInput(id);
    }

    List<Long> expiredInputs(LocalDateTime threshold) {
        return records.selectListByQuery(QueryWrapper.create().select(DOC_GEN_RECORD.ID)
                        .where(DOC_GEN_RECORD.FINISHED_AT.le(threshold)).and(DOC_GEN_RECORD.INPUT_JSON.isNotNull())
                        .orderBy(DOC_GEN_RECORD.FINISHED_AT.asc(), DOC_GEN_RECORD.ID.asc()).limit(100)).stream()
                .map(DocGenRecord::getId).toList();
    }

    List<Long> stale(LocalDateTime threshold) {
        return records.selectListByQuery(QueryWrapper.create().select(DOC_GEN_RECORD.ID)
                        .where(DOC_GEN_RECORD.STATUS.eq("RUNNING")).and(DOC_GEN_RECORD.CREATED_AT.le(threshold))
                        .orderBy(DOC_GEN_RECORD.CREATED_AT.asc(), DOC_GEN_RECORD.ID.asc()).limit(20)).stream()
                .map(DocGenRecord::getId).toList();
    }

    private QueryWrapper owner(Actor actor) {
        return QueryWrapper.create().where(actor.userId() != null
                ? DOC_GEN_RECORD.CREATED_BY.eq(actor.userId()) : DOC_GEN_RECORD.CREATED_BY_APP_ID.eq(actor.appId()));
    }

    private QueryWrapper versionScope(long templateId) {
        return QueryWrapper.create().where(DOC_GEN_VERSION.TEMPLATE_ID.eq(templateId));
    }

    private long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private String contains(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
