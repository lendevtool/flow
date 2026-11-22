package me.lendev.flow.docgen;

import static me.lendev.flow.audit.AuditField.visible;
import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.docgen.DocGenerationModels.*;
import static me.lendev.flow.docgen.DocGenerationSupport.*;
import static me.lendev.flow.document.DocumentGenerationFiles.Target.TEMPLATE_VERSION;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.docgen.entity.DocGenTemplate;
import me.lendev.flow.docgen.entity.DocGenVersion;
import me.lendev.flow.docgen.render.DocxRenderer;
import me.lendev.flow.docgen.render.RenderFailure;
import me.lendev.flow.document.DocumentCenter;
import me.lendev.flow.document.DocumentGenerationFiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理模板草稿、发布版本及其不可变的文件和渲染配置。
 */
@Service
@Slf4j
class DocGenerationTemplates {
    private final DocGenerationRepository repository;
    private final DocumentGenerationFiles files;
    private final DocGenerationTemplateGraph graph;
    private final DocGenerationAudit audit;
    private final DocGenerationQuota quota;
    private final DocxRenderer renderer;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;

    DocGenerationTemplates(DocGenerationRepository repository, DocumentGenerationFiles files,
                           DocGenerationTemplateGraph graph, DocxRenderer renderer,
                           DocGenerationAudit audit, DocGenerationQuota quota,
                           ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.files = files;
        this.graph = graph;
        this.audit = audit;
        this.quota = quota;
        this.renderer = renderer;
        this.json = json;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    List<TemplateSummary> available() {
        return repository.availableTemplates().stream().map(this::summary).toList();
    }

    CurrentTemplate current(long templateId) {
        var template = require(templateId, false);
        Boolean enabled = template.getEnabled();
        if (enabled == null || !enabled.booleanValue() || template.getCurrentVersionId() == null) {
            throw DocGenerationFailure.templateMissing();
        }
        var version = version(templateId, template.getCurrentVersionId(), false);
        return new CurrentTemplate(id(template.getId()), template.getName(), id(version.getId()), version.getVersionNo(),
                version.getInputSchema(), version.getExampleJson(), samples(version));
    }

    Slice<TemplateSummary> list(TemplateQuery filter) {
        page(filter.pageNo(), filter.pageSize());
        text(filter.keyword(), 200, "关键词", false);
        var slice = repository.templates(filter);
        return new Slice<>(slice.records().stream().map(this::summary).toList(), slice.total());
    }

    CreatedTemplate create(CreateTemplate request, int actor) {
        String code = text(request.code(), 64, "模板编码", true);
        if (!code.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw DocGenerationFailure.invalid("模板编码格式无效");
        String name = text(request.name(), 200, "模板名称", true);
        String description = text(request.description(), 500, "模板说明", false);
        try {
            return transactions.execute(status -> {
                var row = new DocGenTemplate();
                row.setCode(code);
                row.setName(name);
                row.setDescription(description);
                row.setEnabled(true);
                row.setCreatedBy(actor);
                row.setUpdatedBy(actor);
                row.setCreatedAt(now(clock));
                row.setUpdatedAt(row.getCreatedAt());
                repository.insert(row);
                audit.template(DOCGEN_TEMPLATE_CREATE, row.getId(), Map.of("code", visible("模板编码", code)));
                return new CreatedTemplate(summary(row), view(newDraft(row, null, actor)));
            });
        } catch (DuplicateKeyException existing) {
            throw DocGenerationFailure.conflict("TEMPLATE_CODE_CONFLICT", "模板编码已存在");
        }
    }

    TemplateSummary edit(long templateId, EditTemplate request, int actor) {
        return transactions.execute(status -> {
            var row = require(templateId, true);
            if (request.name() != null) row.setName(text(request.name(), 200, "模板名称", true));
            if (request.description() != null) row.setDescription(text(request.description(), 500, "模板说明", false));
            if (request.enabled() != null) row.setEnabled(request.enabled());
            row.setUpdatedBy(actor);
            row.setUpdatedAt(nextTime(clock, row.getUpdatedAt()));
            repository.save(row);
            audit.template(DOCGEN_TEMPLATE_EDIT, row.getId(), Map.of("enabled", visible("启用", row.getEnabled())));
            return summary(row);
        });
    }

    List<VersionView> versions(long templateId) {
        require(templateId, false);
        return repository.versions(templateId).stream().map(this::view).toList();
    }

    VersionView detail(long templateId, long versionId) {
        return view(version(templateId, versionId, false));
    }

    VersionView createVersion(long templateId, NewVersion request, int actor) {
        var sourceId = request == null ? null : request.sourceVersionId();
        var source = sourceId == null ? null : version(templateId, id(sourceId), false);
        if (source != null && !PUBLISHED.equals(source.getStatus())) {
            throw DocGenerationFailure.conflict("VERSION_CONFLICT", "复制来源必须是已发布版本");
        }
        byte[] content = source == null ? null : graph.copyFile(source, actor);
        var created = transactions.execute(status -> view(newDraft(require(templateId, true), source, actor)));
        if (content == null) return created;
        return upload(templateId, id(created.id()), created.etag(), "template.docx", content, actor);
    }

    VersionView save(long templateId, long versionId, String etag, VersionInput input, int actor) {
        validateConfiguration(input);
        return transactions.execute(status -> {
            var row = draft(templateId, versionId, etag, true);
            row.setInputSchema(input.inputSchema());
            row.setExampleJson(nullable(input.exampleJson()));
            row.setRenderConfig(nullable(input.renderConfig()));
            row.setSamplesJson(nullable(input.samples()));
            touch(row, actor);
            repository.save(row);
            audit.version(DOCGEN_VERSION_EDIT, row.getId(), templateId,
                    Map.of("samples", visible("样例数量", samples(row).size())));
            return view(row);
        });
    }

    VersionView upload(long templateId, long versionId, String etag, String fileName, byte[] content, int actor) {
        var existing = draft(templateId, versionId, etag, false);
        byte[] normalized = formatted(content);
        DocumentCenter.Version uploaded;
        try {
            var file = new DocumentCenter.Upload(fileName, DOCX_TYPE, normalized.length, new ByteArrayInputStream(normalized));
            uploaded = files.upload(TEMPLATE_VERSION, versionId, existing.getDocumentId(), actor,
                    require(templateId, false).getName(), file);
        } catch (RuntimeException failure) {
            log.warn("模板文件上传失败，versionId={}，errorType={}", versionId, failure.getClass().getSimpleName());
            throw unavailable();
        }
        try {
            return transactions.execute(status -> {
                var row = draft(templateId, versionId, etag, true);
                files.bind(TEMPLATE_VERSION, versionId, uploaded.documentId(), uploaded.id(), actor);
                row.setDocumentId(uploaded.documentId());
                row.setDocumentVersionId(uploaded.id());
                touch(row, actor);
                repository.save(row);
                audit.version(DOCGEN_VERSION_EDIT, row.getId(), templateId,
                        Map.of("documentVersionId", visible("文件版本", uploaded.id())));
                return view(row);
            });
        } catch (RuntimeException failure) {
            try {
                files.discard(TEMPLATE_VERSION, versionId, uploaded.documentId(), uploaded.id(), actor);
            } catch (RuntimeException cleanup) {
                log.warn("模板文件补偿失败，versionId={}，documentVersionId={}", versionId, uploaded.id());
            }
            throw failure;
        }
    }

    VersionView publish(long templateId, long versionId, String etag, int actor) {
        var requested = draft(templateId, versionId, etag, false);
        quota.run(() -> {
            graph.validateForPublication(requested, actor);
            return null;
        });
        return transactions.execute(status -> {
            var template = require(templateId, true);
            if (template.getCurrentVersionId() != null) {
                var current = version(templateId, template.getCurrentVersionId(), false);
                if (current.getVersionNo() >= requested.getVersionNo()) {
                    throw DocGenerationFailure.conflict("VERSION_CONFLICT", "请发布比当前版本更新的草稿");
                }
            }
            var draft = draft(templateId, versionId, etag, true);
            draft.setStatus(PUBLISHED);
            draft.setPublishedBy(actor);
            draft.setPublishedAt(now(clock));
            touch(draft, actor);
            repository.save(draft);
            template.setCurrentVersionId(versionId);
            template.setUpdatedAt(nextTime(clock, template.getUpdatedAt()));
            template.setUpdatedBy(actor);
            repository.save(template);
            audit.version(DOCGEN_VERSION_PUBLISH, versionId, templateId,
                    Map.of("versionNo", visible("发布版本号", draft.getVersionNo())));
            log.info("文档模板版本已发布，templateId={}，versionId={}，actor={}", templateId, versionId, actor);
            return view(draft);
        });
    }

    Preview preview(long templateId, long versionId, String token, JsonNode input, int actor) {
        return quota.run(() -> {
            var draft = draft(templateId, versionId, token, false);
            try {
                byte[] file = graph.render(draft, input, actor);
                transactions.executeWithoutResult(status -> audit.version(DOCGEN_VERSION_TEST, versionId, templateId, Map.of()));
                return new Preview(require(templateId, false).getName() + "-试生成.docx", file);
            } catch (RenderFailure failure) {
                throw renderFailure(failure, false);
            }
        });
    }

    void download(long templateId, long versionId, int actor, DocumentCenter.Download writer) {
        var row = version(templateId, versionId, false);
        if (row.getDocumentVersionId() == null) throw DocGenerationFailure.templateMissing();
        files.download(TEMPLATE_VERSION, row.getId(), row.getDocumentId(), row.getDocumentVersionId(), actor, (metadata, stream) -> {
            // 已发布模板的 SFTP 版本固定。下载旧版本时返回更新格式后的副本。
            byte[] normalized = formatted(stream.readNBytes(DocxRenderer.MAX_DOCX_BYTES + 1));
            var version = new DocumentCenter.Version(metadata.id(), metadata.documentId(), metadata.versionNo(),
                    metadata.originalFileName(), metadata.downloadName(), DOCX_TYPE, normalized.length, metadata.createdAt());
            writer.write(version, new ByteArrayInputStream(normalized));
        });
    }

    private byte[] formatted(byte[] content) {
        try {
            return renderer.normalizeTemplate(content);
        } catch (RenderFailure failure) {
            throw renderFailure(failure, true);
        }
    }

    long seed(SeedTemplate seed) {
        var existing = repository.templateByCode(seed.code());
        if (existing != null && existing.getCreatedBy() != 0) {
            throw DocGenerationFailure.conflict("TEMPLATE_CODE_CONFLICT", "样例编码已由人工模板使用");
        }
        if (existing != null && existing.getCurrentVersionId() != null) return existing.getCurrentVersionId();
        long templateId;
        VersionView version;
        if (existing == null) {
            var newTemplate = create(new CreateTemplate(seed.code(), seed.name(), seed.description()), 0);
            templateId = id(newTemplate.template().id());
            version = newTemplate.version();
        } else {
            templateId = existing.getId();
            version = view(repository.latest(templateId));
        }
        var options = new VersionInput(seed.inputSchema(), null, seed.renderConfig(), seed.samples());
        var configured = save(templateId, id(version.id()), version.etag(), options, 0);
        var uploaded = upload(templateId, id(version.id()), configured.etag(), seed.code() + ".docx", seed.file(), 0);
        return id(publish(templateId, id(version.id()), uploaded.etag(), 0).id());
    }

    private DocGenVersion newDraft(DocGenTemplate template, DocGenVersion source, int actor) {
        var latest = repository.latest(template.getId());
        var row = new DocGenVersion();
        row.setTemplateId(template.getId());
        row.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        row.setStatus(DRAFT);
        row.setTemplateFormat("POI_TL_V1");
        row.setInputSchema(source == null ? json.readTree("{\"type\":\"object\"}") : source.getInputSchema());
        if (source != null) {
            row.setExampleJson(source.getExampleJson());
            row.setRenderConfig(source.getRenderConfig());
            row.setSamplesJson(source.getSamplesJson());
        }
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now(clock));
        row.setUpdatedAt(row.getCreatedAt());
        repository.insert(row);
        audit.version(DOCGEN_VERSION_EDIT, row.getId(), template.getId(),
                Map.of("versionNo", visible("草稿版本号", row.getVersionNo())));
        return row;
    }

    private void validateConfiguration(VersionInput input) {
        if (input.inputSchema() == null || !input.inputSchema().isObject()
                || nullable(input.exampleJson()) != null && !input.exampleJson().isObject()
                || nullable(input.renderConfig()) != null && !input.renderConfig().isObject()) {
            throw DocGenerationFailure.invalid("Schema、默认样例和渲染配置必须是 JSON 对象");
        }
        limit(input.inputSchema(), 128 * 1024);
        limit(input.renderConfig(), 64 * 1024);
        limit(input.exampleJson(), 4 * 1024 * 1024);
        limit(input.samples(), 4 * 1024 * 1024);
        if (nullable(input.samples()) == null) return;
        if (!input.samples().isArray() || input.samples().size() > 50) {
            throw DocGenerationFailure.invalid("样例必须是最多 50 项的数组");
        }
        var seen = new HashSet<String>();
        for (var sample : input.samples()) {
            String code = sample.path("code").asString("");
            if (!sample.isObject() || !code.matches("[a-z0-9][a-z0-9_-]{0,63}") || !seen.add(code)
                    || !sample.path("name").isString() || sample.path("name").asString().isBlank()
                    || sample.path("name").asString().length() > 200 || !sample.path("input").isObject()) {
                throw DocGenerationFailure.invalid("样例需要唯一编码、名称和对象输入");
            }
        }
    }

    private void limit(JsonNode node, int maximum) {
        if (nullable(node) != null && json.writeValueAsBytes(node).length > maximum) {
            throw new DocGenerationFailure(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "模板配置超过大小限制");
        }
    }

    private DocGenVersion draft(long templateId, long versionId, String token, boolean lock) {
        var row = version(templateId, versionId, lock);
        String current = etag(row);
        if (!DRAFT.equals(row.getStatus()) || !Objects.equals(token, current)) {
            throw DocGenerationFailure.staleDraft(current);
        }
        return row;
    }

    private void touch(DocGenVersion row, int actor) {
        row.setUpdatedAt(nextTime(clock, row.getUpdatedAt()));
        row.setUpdatedBy(actor);
    }

    private String etag(DocGenVersion row) {
        return "\"" + row.getId() + "-" + instant(row.getUpdatedAt()).toEpochMilli() + "\"";
    }

    DocGenTemplate require(long id, boolean lock) {
        var row = repository.template(id, lock);
        if (row == null) throw DocGenerationFailure.templateMissing();
        return row;
    }

    DocGenVersion version(long templateId, long versionId, boolean lock) {
        var row = repository.version(versionId, lock);
        if (row == null || row.getTemplateId() != templateId) throw DocGenerationFailure.templateMissing();
        return row;
    }

    private TemplateSummary summary(DocGenTemplate row) {
        return new TemplateSummary(id(row.getId()), row.getCode(), row.getName(), row.getDescription(),
                row.getEnabled(), id(row.getCurrentVersionId()));
    }

    private JsonNode samples(DocGenVersion row) {
        return row.getSamplesJson() == null ? json.createArrayNode() : row.getSamplesJson();
    }

    private VersionView view(DocGenVersion row) {
        return new VersionView(id(row.getId()), id(row.getTemplateId()), row.getVersionNo(), row.getStatus(),
                row.getTemplateFormat(), row.getInputSchema(), row.getExampleJson(),
                row.getRenderConfig() == null ? json.createObjectNode() : row.getRenderConfig(), samples(row),
                id(row.getDocumentId()), id(row.getDocumentVersionId()), etag(row), instant(row.getUpdatedAt()));
    }
}
