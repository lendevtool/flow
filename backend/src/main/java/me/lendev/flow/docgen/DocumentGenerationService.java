package me.lendev.flow.docgen;

import static me.lendev.flow.audit.AuditField.visible;
import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.docgen.DocGenerationModels.*;
import static me.lendev.flow.docgen.DocGenerationSupport.*;
import static me.lendev.flow.document.DocumentGenerationFiles.Target.GENERATION;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.docgen.entity.DocGenRecord;
import me.lendev.flow.docgen.entity.DocGenVersion;
import me.lendev.flow.docgen.render.RenderFailure;
import me.lendev.flow.document.DocumentCenter;
import me.lendev.flow.document.DocumentGenerationFiles;
import me.lendev.flow.document.SftpDocumentStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 文档生成服务。员工与开放应用拥有独立记录和请求幂等空间。
 */
@Service
@Slf4j
public class DocumentGenerationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int INPUT_BYTES = 4 * 1024 * 1024;
    private static final int INPUT_DEPTH = 32;
    private static final Set<String> STATUSES = Set.of(RUNNING, SUCCEEDED, FAILED);

    private final DocGenerationRepository repository;
    private final DocGenerationTemplates templates;
    private final DocumentGenerationFiles files;
    private final SftpDocumentStorageProperties sftp;
    private final DocGenerationAudit audit;
    private final DocGenerationTemplateGraph graph;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Duration retention;
    private final Duration runningTimeout;
    private final DocGenerationQuota quota;
    private final long maxOutputBytes;

    DocumentGenerationService(DocGenerationRepository repository, DocGenerationTemplates templates,
                              DocumentGenerationFiles files, SftpDocumentStorageProperties sftp, DocGenerationTemplateGraph graph,
                              ObjectMapper json, DocGenerationAudit audit,
                              Clock clock, PlatformTransactionManager manager, DocGenerationQuota quota,
                              @Value("${document.generation.input-retention:30d}") Duration retention,
                              @Value("${document.generation.running-timeout:5m}") Duration runningTimeout,
                              @Value("${spring.servlet.multipart.max-file-size:5MB}") DataSize maxOutputBytes) {
        if (retention.isNegative() || runningTimeout.isZero() || runningTimeout.isNegative()) {
            throw new IllegalArgumentException("文档生成保留期、处理超时或并发配置无效");
        }
        this.repository = repository;
        this.templates = templates;
        this.files = files;
        this.sftp = sftp;
        this.audit = audit;
        this.graph = graph;
        this.json = json;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.retention = retention;
        this.runningTimeout = runningTimeout;
        this.quota = quota;
        this.maxOutputBytes = maxOutputBytes.toBytes();
    }

    /**
     * 列出启用且已有发布版本的模板。
     */
    public List<TemplateSummary> availableTemplates() {
        return templates.available();
    }

    /**
     * 读取可用于发起生成的当前发布版本。
     */
    public CurrentTemplate availableTemplate(long id) {
        return templates.current(id);
    }

    /**
     * 以已授权的员工或应用身份生成 DOCX，相同幂等请求返回原记录。
     */
    public GenerationView generate(Actor actor, String requestKey, long templateVersionId, JsonNode input) {
        String key = normalizeKey(requestKey);
        String hash = hash(templateVersionId, input);
        var repeated = repository.byRequest(actor, key);
        if (repeated != null) return replay(repeated, hash);
        return quota.run(() -> actor.appId() == null ? renderNew(actor, key, hash, templateVersionId, input)
                : system("文档生成开放应用 " + actor.appId(), () -> renderNew(actor, key, hash, templateVersionId, input)));
    }

    /**
     * 读取指定主体自己的生成记录。
     */
    public GenerationView generation(Actor actor, long id) {
        return view(require(actor, id));
    }

    /**
     * 通过文档中心输出指定主体已经成功生成的固定成品文件。
     */
    public void download(Actor actor, long id, DocumentCenter.Download writer) {
        var row = require(actor, id);
        if (!SUCCEEDED.equals(row.getStatus())) {
            throw DocGenerationFailure.conflict("GENERATION_FILE_UNAVAILABLE", "成品尚不可下载");
        }
        try {
            if (actor.appId() == null) {
                readFile(actor, row, writer);
            } else {
                system("文档生成开放应用 " + actor.appId(), () -> {
                    readFile(actor, row, writer);
                    return null;
                });
            }
        } catch (RuntimeException failure) {
            log.warn("生成成品读取失败，generationId={}，errorType={}", id, failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    /**
     * 幂等初始化模板及其样例，返回已发布的模板版本 ID。
     */
    public long seedTemplate(SeedTemplate specification) {
        return system("文档生成样例初始化", () -> templates.seed(specification));
    }

    Slice<GenerationView> history(Actor actor, GenerationQuery filter) {
        page(filter.pageNo(), filter.pageSize());
        if (filter.templateId() != null && filter.templateId() <= 0
                || filter.status() != null && !STATUSES.contains(filter.status())
                || filter.startDate() != null && filter.endDate() != null && filter.startDate().isAfter(filter.endDate())) {
            throw DocGenerationFailure.invalid("生成记录筛选条件无效");
        }
        var result = repository.history(actor, filter, dateBoundary(filter.startDate(), false),
                dateBoundary(filter.endDate(), true));
        return new Slice<>(result.records().stream().map(this::view).toList(), result.total());
    }

    Slice<FileView> files(int actor, FileQuery filter) {
        page(filter.pageNo(), filter.pageSize());
        String keyword = text(filter.keyword(), 255, "关键词", false);
        var result = repository.files(actor, new FileQuery(filter.type(), filter.pageNo(), filter.pageSize(), keyword));
        return new Slice<>(result.records().stream().map(row -> new FileView(filter.type(), id(row.getTemplateId()),
                id(row.getTemplateVersionId()), row.getTemplateName(), row.getVersionNo(), id(row.getGenerationId()),
                row.getOriginalFileName(), row.getSizeBytes(), instant(row.getCreatedAt()), row.getStatus(),
                sftp.relativePath(row.getStorageKey()))).toList(), result.total());
    }

    JsonNode input(Actor actor, long id) {
        var found = require(actor, id);
        if (!hasInput(found)) throw new DocGenerationFailure(HttpStatus.GONE, "INPUT_EXPIRED", "输入快照已到期");
        return found.getInputJson();
    }

    /**
     * 有界回收已到期快照，并确认处理超时记录的最终状态。
     */
    public void maintain() {
        system("文档生成记录清理", () -> {
            var time = now(clock);
            int purged = purgeExpiredInputs(time.minus(retention));
            var cutoff = time.minus(runningTimeout);
            var stale = repository.stale(cutoff);
            if (purged > 0 || !stale.isEmpty()) {
                log.info("文档生成定时清理，purgedInputs={}，staleScanned={}", purged, stale.size());
            }
            recoverStaleGenerations(stale);
            reclaimUnadoptedFiles(cutoff);
            return null;
        });
    }

    private int purgeExpiredInputs(LocalDateTime expiration) {
        int purged = 0;
        for (long id : repository.expiredInputs(expiration)) {
            if (Boolean.TRUE.equals(transactions.execute(status -> purge(id, expiration)))) purged++;
        }
        return purged;
    }

    private void recoverStaleGenerations(List<Long> stale) {
        for (long id : stale) {
            try {
                var unused = transactions.execute(status -> recover(id));
                if (unused != null) unused.forEach(version -> discard(id, version, 0));
            } catch (RuntimeException failure) {
                log.warn("恢复生成记录失败，generationId={}，errorType={}", id, failure.getClass().getSimpleName());
            }
        }
    }

    private void reclaimUnadoptedFiles(LocalDateTime cutoff) {
        for (var candidate : files.unadopted(cutoff)) {
            try {
                transactions.executeWithoutResult(status -> reclaim(candidate));
            } catch (RuntimeException failure) {
                log.warn("回收未绑定的文档失败，documentVersionId={}，errorType={}",
                        candidate.versionId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void reclaim(DocumentGenerationFiles.Unadopted candidate) {
        if (candidate.target() == GENERATION) {
            var generation = repository.generation(candidate.recordId(), true);
            if (generation != null && (RUNNING.equals(generation.getStatus())
                    || Objects.equals(generation.getOutputVersionId(), candidate.versionId()))) return;
        } else {
            var version = repository.version(candidate.recordId(), true);
            if (version != null && Objects.equals(version.getDocumentVersionId(), candidate.versionId())) return;
        }
        files.discard(candidate.target(), candidate.recordId(), candidate.documentId(), candidate.versionId(), 0);
    }

    private GenerationView renderNew(Actor actor, String key, String hash, long versionId, JsonNode input) {
        var selected = currentVersion(versionId, false);
        try {
            graph.validateInput(selected, input);
        } catch (RenderFailure failure) {
            throw renderFailure(failure, false);
        }
        DocGenRecord row;
        try {
            row = transactions.execute(status -> reserve(actor, key, hash, versionId, input));
        } catch (DuplicateKeyException duplicate) {
            var original = repository.byRequest(actor, key);
            if (original == null) throw duplicate;
            return replay(original, hash);
        }
        byte[] rendered;
        try {
            rendered = graph.render(selected, input, actor.fileActor());
        } catch (RuntimeException failure) {
            String code = switch (failure) {
                case DocGenerationFailure businessFailure -> businessFailure.code();
                case RenderFailure renderFailure -> renderFailure.code();
                default -> "RENDER_FAILED";
            };
            return failed(row, code, failure);
        }
        if (rendered.length > maxOutputBytes) {
            return failed(row, "FILE_TOO_LARGE", null);
        }
        var templateName = templates.require(selected.getTemplateId(), false).getName();
        var filename = templateName.replace('/', '-').replace('\\', '-').replaceAll("\\p{Cntrl}", "")
                + "-" + row.getId() + ".docx";
        DocumentCenter.Version uploaded;
        long uploadStarted = System.nanoTime();
        try (var source = new ByteArrayInputStream(rendered)) {
            var content = new DocumentCenter.Upload(filename, DOCX_TYPE, rendered.length, source);
            uploaded = files.upload(GENERATION, row.getId(), null, actor.fileActor(), filename, content);
            log.info("成品已上传，generationId={}，uploadElapsedMs={}", row.getId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - uploadStarted));
        } catch (RuntimeException | java.io.IOException failure) {
            log.warn("成品上传失败，generationId={}，uploadElapsedMs={}", row.getId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - uploadStarted));
            return failed(row, "STORAGE_UNAVAILABLE", failure);
        }
        try {
            var completed = transactions.execute(status -> accept(row.getId(), uploaded, actor.fileActor()));
            if (!SUCCEEDED.equals(completed.getStatus())
                    || !Objects.equals(uploaded.id(), completed.getOutputVersionId())) {
                discard(row.getId(), uploaded, actor.fileActor());
            }
            return view(completed);
        } catch (RuntimeException failure) {
            log.warn("成品已上传，生成状态等待补偿，generationId={}，versionId={}，errorType={}",
                    row.getId(), uploaded.id(), failure.getClass().getSimpleName());
            return view(row);
        }
    }

    private DocGenRecord reserve(Actor actor, String key, String hash, long versionId, JsonNode input) {
        currentVersion(versionId, true);
        var row = new DocGenRecord();
        row.setTemplateVersionId(versionId);
        row.setCreatedBy(actor.userId());
        row.setCreatedByAppId(actor.appId());
        row.setRequestKey(key);
        row.setRequestHash(hash);
        row.setInputJson(input);
        row.setStatus(RUNNING);
        row.setCreatedAt(now(clock));
        repository.insert(row);
        audit.generation(DOCGEN_CREATE, row.getId(), Map.of("templateVersionId", visible("模板版本", versionId)));
        return row;
    }

    private DocGenVersion currentVersion(long id, boolean lockTemplate) {
        var version = repository.version(id, false);
        if (version == null || !PUBLISHED.equals(version.getStatus())) throw DocGenerationFailure.templateMissing();
        var template = templates.require(version.getTemplateId(), lockTemplate);
        Boolean enabled = template.getEnabled();
        if (enabled == null || !enabled.booleanValue() || !Objects.equals(template.getCurrentVersionId(), id)) {
            throw DocGenerationFailure.conflict("TEMPLATE_VERSION_CHANGED", "模板已停用或切换版本，请刷新");
        }
        return version;
    }

    private DocGenRecord accept(long id, DocumentCenter.Version file, int actor) {
        var row = repository.generation(id, true);
        if (RUNNING.equals(row.getStatus())) {
            files.bind(GENERATION, id, file.documentId(), file.id(), actor);
            row.setOutputDocumentId(file.documentId());
            row.setOutputVersionId(file.id());
            row.setStatus(SUCCEEDED);
            row.setFinishedAt(now(clock));
            repository.save(row);
            audit.generation(DOCGEN_FINISH, id, Map.of("status", visible("生成状态", SUCCEEDED),
                    "outputVersionId", visible("成品文件版本", file.id())));
            log.info("文档生成成功，generationId={}，templateVersionId={}，versionId={}，elapsedMs={}",
                    id, row.getTemplateVersionId(), file.id(), Duration.between(row.getCreatedAt(), row.getFinishedAt()).toMillis());
        }
        return row;
    }

    private GenerationView failed(DocGenRecord row, String code, Exception failure) {
        if (failure != null) {
            log.warn("文档生成失败，generationId={}，code={}，errorType={}",
                    row.getId(), code, failure.getClass().getSimpleName());
        }
        return view(transactions.execute(status -> {
            var current = repository.generation(row.getId(), true);
            if (RUNNING.equals(current.getStatus())) fail(current, code);
            return current;
        }));
    }

    private void fail(DocGenRecord row, String code) {
        row.setStatus(FAILED);
        row.setFinishedAt(now(clock));
        row.setErrorCode(code);
        repository.save(row);
        audit.generation(DOCGEN_FINISH, row.getId(),
                Map.of("status", visible("生成状态", FAILED), "errorCode", visible("失败原因", code)));
        log.info("文档生成结束，generationId={}，code={}，elapsedMs={}", row.getId(), code,
                Duration.between(row.getCreatedAt(), row.getFinishedAt()).toMillis());
    }

    private List<DocumentCenter.Version> recover(long id) {
        var row = repository.generation(id, true);
        if (row == null || !RUNNING.equals(row.getStatus())) return List.of();
        var uploaded = files.readyGeneration(id, 0);
        if (uploaded.size() == 1) {
            accept(id, uploaded.getFirst(), 0);
            return List.of();
        }
        fail(row, uploaded.isEmpty() ? "GENERATION_TIMEOUT" : "GENERATION_OUTPUT_CONFLICT");
        log.warn("等待生成结果超时，generationId={}，候选文件版本={}", id,
                uploaded.stream().map(DocumentCenter.Version::id).toList());
        return uploaded;
    }

    private boolean purge(long id, LocalDateTime threshold) {
        var row = repository.generation(id, true);
        if (row != null && row.getFinishedAt() != null && !row.getFinishedAt().isAfter(threshold)
                && row.getInputJson() != null) {
            repository.clearInput(id);
            audit.generation(DOCGEN_PURGE_INPUT, id, Map.of("purged", visible("输入快照已清理", true)));
            return true;
        }
        return false;
    }

    private void discard(long id, DocumentCenter.Version file, int actor) {
        try {
            files.discard(GENERATION, id, file.documentId(), file.id(), actor);
        } catch (RuntimeException failure) {
            log.warn("未采用的生成文件暂未清理，generationId={}，versionId={}", id, file.id());
        }
    }

    private void readFile(Actor actor, DocGenRecord row, DocumentCenter.Download writer) {
        files.download(GENERATION, row.getId(), row.getOutputDocumentId(), row.getOutputVersionId(), actor.fileActor(), writer);
    }

    private DocGenRecord require(Actor actor, long id) {
        var row = repository.generation(id, false);
        if (row == null || !Objects.equals(row.getCreatedBy(), actor.userId())
                || !Objects.equals(row.getCreatedByAppId(), actor.appId()))
            throw DocGenerationFailure.generationMissing();
        return row;
    }

    private GenerationView replay(DocGenRecord found, String hash) {
        if (!found.getRequestHash().equals(hash)) {
            throw DocGenerationFailure.conflict("REQUEST_KEY_CONFLICT", "同一请求键已用于不同输入");
        }
        return view(found);
    }

    private GenerationView view(DocGenRecord row) {
        var version = repository.version(row.getTemplateVersionId(), false);
        var template = templates.require(version.getTemplateId(), false);
        return new GenerationView(id(row.getId()), id(template.getId()), id(version.getId()), template.getName(),
                version.getVersionNo(), row.getStatus(), row.getErrorCode(), instant(row.getCreatedAt()),
                instant(row.getFinishedAt()), hasInput(row), SUCCEEDED.equals(row.getStatus()));
    }

    private boolean hasInput(DocGenRecord row) {
        return row.getInputJson() != null
                && (row.getFinishedAt() == null || row.getFinishedAt().isAfter(now(clock).minus(retention)));
    }

    private String normalizeKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw DocGenerationFailure.invalid("Idempotency-Key 必须为标准 UUID");
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private String hash(long versionId, JsonNode input) {
        if (input == null || !input.isObject()) throw DocGenerationFailure.invalid("生成输入必须是 JSON 对象");
        byte[] content = json.writeValueAsBytes(canonical(input, 0));
        if (content.length > INPUT_BYTES) {
            throw new DocGenerationFailure(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "生成输入超过 4 MiB");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((versionId + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JVM 不支持 SHA-256", unavailable);
        }
    }

    private JsonNode canonical(JsonNode node, int depth) {
        if (depth > INPUT_DEPTH) throw DocGenerationFailure.invalid("生成输入嵌套层级过深");
        if (node.isObject()) {
            var sorted = json.createObjectNode();
            node.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(field -> sorted.set(field.getKey(), canonical(field.getValue(), depth + 1)));
            return sorted;
        }
        if (node.isArray()) {
            var sorted = json.createArrayNode();
            for (var entry : node) sorted.add(canonical(entry, depth + 1));
            return sorted;
        }
        return node;
    }

    private LocalDateTime dateBoundary(LocalDate date, boolean exclusive) {
        if (date == null) return null;
        if (date.getYear() < 1970 || date.getYear() > 9998) {
            throw DocGenerationFailure.invalid("生成记录查询日期超出范围");
        }
        var day = exclusive ? date.plusDays(1) : date;
        return day.atStartOfDay(BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
