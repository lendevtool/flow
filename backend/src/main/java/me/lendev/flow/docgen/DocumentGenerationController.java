package me.lendev.flow.docgen;

import static me.lendev.flow.common.auth.PermissionCode.DOCUMENT_CENTER_VIEW;
import static me.lendev.flow.docgen.DocGenerationModels.*;
import static me.lendev.flow.docgen.DocGenerationSupport.*;
import static me.lendev.flow.document.DocumentGenerationFiles.Target.GENERATION;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.response.R;
import me.lendev.flow.docgen.render.DocxRenderer;
import me.lendev.flow.document.DocumentGenerationFiles.Target;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * 业务信息中的文档生成、个人历史与模板管理入口。
 */
@RestController
@RequestMapping("/document-generation")
@RequiredArgsConstructor
public class DocumentGenerationController {
    private static final String VIEW = "DocumentGenerationView";
    private static final String RUN = "DocumentGenerationRun";
    private static final String READ_FILE = "DocumentGenerationReadFile";
    private static final String MANAGE = "DocumentGenerationTemplateManage";
    private static final String PUBLISH = "DocumentGenerationTemplatePublish";
    private static final MediaType DOCX = MediaType.parseMediaType(DOCX_TYPE);
    private static final String ADMIN = "/admin/templates";
    private static final String VERSIONS = ADMIN + "/{templateId}/versions";
    private static final String VERSION = VERSIONS + "/{versionId}";

    private final DocumentGenerationService generations;
    private final DocGenerationTemplates templates;

    @GetMapping("/templates")
    public R<List<TemplateSummary>> available() {
        permit(VIEW);
        return R.ok(generations.availableTemplates());
    }

    /**
     * 查询文档中心中已绑定的模板文件或员工本人的生成成品。
     *
     * @param type     文件类型，模板需要模板管理权限，成品需要文档生成查看权限
     * @param pageNo   页码，默认 1
     * @param pageSize 每页条数，默认 20，最大 100
     * @param keyword  可选的模板名称、模板编码或原始文件名关键词
     * @return 按文件版本倒序的元数据列表及筛选后的总条数
     */
    @GetMapping("/files")
    public ResponseEntity<R<Slice<FileView>>> files(@RequestParam Target type,
                                                    @RequestParam(defaultValue = "1") int pageNo,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    @RequestParam(required = false) String keyword) {
        permit(DOCUMENT_CENTER_VIEW);
        int actor = permit(type == GENERATION ? VIEW : MANAGE);
        var result = generations.files(actor, new FileQuery(type, pageNo, pageSize, keyword));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(result));
    }

    @GetMapping("/templates/{templateId}/current")
    public ResponseEntity<R<CurrentTemplate>> current(@PathVariable long templateId) {
        permit(VIEW);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(generations.availableTemplate(templateId)));
    }

    @GetMapping(ADMIN)
    public R<Slice<TemplateSummary>> templates(@RequestParam(defaultValue = "1") int pageNo,
                                               @RequestParam(defaultValue = "20") int pageSize,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Boolean enabled,
                                               @RequestParam(required = false) Boolean published) {
        permit(MANAGE);
        return R.ok(templates.list(new TemplateQuery(pageNo, pageSize, keyword, enabled, published)));
    }

    @PostMapping(ADMIN)
    public R<CreatedTemplate> create(@RequestBody CreateTemplate input) {
        return R.ok(templates.create(input, permit(MANAGE)));
    }

    @PatchMapping(ADMIN + "/{templateId}")
    public R<TemplateSummary> edit(@PathVariable long templateId, @RequestBody EditTemplate input) {
        return R.ok(templates.edit(templateId, input, permit(MANAGE)));
    }

    @GetMapping(VERSIONS)
    public R<List<VersionView>> listVersions(@PathVariable long templateId) {
        permit(MANAGE);
        return R.ok(templates.versions(templateId));
    }

    @PostMapping(VERSIONS)
    public ResponseEntity<R<VersionView>> createVersion(@PathVariable long templateId,
                                                        @RequestBody(required = false) NewVersion input) {
        return response(templates.createVersion(templateId, input, permit(MANAGE)));
    }

    @GetMapping(VERSION)
    public ResponseEntity<R<VersionView>> version(@PathVariable long templateId, @PathVariable long versionId) {
        permit(MANAGE);
        return response(templates.detail(templateId, versionId));
    }

    @PutMapping(VERSION)
    public ResponseEntity<R<VersionView>> save(@PathVariable long templateId, @PathVariable long versionId,
                                               @RequestHeader(HttpHeaders.IF_MATCH) String etag,
                                               @RequestBody VersionInput input) {
        return response(templates.save(templateId, versionId, etag, input, permit(MANAGE)));
    }

    @PutMapping(value = VERSION + "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<VersionView>> upload(@PathVariable long templateId, @PathVariable long versionId,
                                                 @RequestHeader(HttpHeaders.IF_MATCH) String etag,
                                                 @RequestPart MultipartFile file) throws IOException {
        int actor = permit(MANAGE);
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new DocGenerationFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "只接受 DOCX 模板");
        }
        byte[] content;
        try (var input = file.getInputStream()) {
            content = input.readNBytes(DocxRenderer.MAX_DOCX_BYTES + 1);
        }
        return response(templates.upload(templateId, versionId, etag, name, content, actor));
    }

    @GetMapping(VERSION + "/file")
    public void templateFile(@PathVariable long templateId, @PathVariable long versionId,
                             HttpServletResponse response) {
        templates.download(templateId, versionId, permit(MANAGE), (metadata, input) -> {
            setFile(response, metadata.downloadName(), metadata.sizeBytes());
            input.transferTo(response.getOutputStream());
        });
    }

    @PostMapping(VERSION + "/test")
    public ResponseEntity<byte[]> preview(@PathVariable long templateId, @PathVariable long versionId,
                                          @RequestHeader(HttpHeaders.IF_MATCH) String etag,
                                          @RequestBody PreviewInput input) {
        var preview = templates.preview(templateId, versionId, etag, input.input(), permit(MANAGE));
        byte[] file = preview.file();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(DOCX)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(preview.fileName()))
                .contentLength(file.length).body(file);
    }

    @PostMapping(VERSION + "/publish")
    public ResponseEntity<R<VersionView>> publish(@PathVariable long templateId, @PathVariable long versionId,
                                                  @RequestHeader(HttpHeaders.IF_MATCH) String etag) {
        permit(MANAGE);
        return response(templates.publish(templateId, versionId, etag, permit(PUBLISH)));
    }

    @PostMapping("/generations")
    public ResponseEntity<R<GenerationView>> generate(@RequestHeader("Idempotency-Key") String key,
                                                      @RequestBody GenerateInput input) {
        var actor = Actor.user(permit(RUN));
        var generated = generations.generate(actor, key, id(input.templateVersionId()), input.input());
        var response = RUNNING.equals(generated.status()) ? ResponseEntity.accepted() : ResponseEntity.ok();
        return response.cacheControl(CacheControl.noStore()).body(R.ok(generated));
    }

    @GetMapping("/generations")
    public ResponseEntity<R<Slice<GenerationView>>> history(@RequestParam(defaultValue = "1") int pageNo,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            @RequestParam(required = false) Long templateId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        var actor = Actor.user(permit(VIEW));
        var result = generations.history(actor, new GenerationQuery(pageNo, pageSize, templateId, status, startDate, endDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(result));
    }

    @GetMapping("/generations/{id}")
    @Operation(operationId = "record")
    public ResponseEntity<R<GenerationView>> generation(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(generations.generation(Actor.user(permit(VIEW)), id)));
    }

    @GetMapping("/generations/{id}/input")
    public ResponseEntity<R<JsonNode>> input(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(generations.input(Actor.user(permit(RUN)), id)));
    }

    @GetMapping("/generations/{id}/file")
    public void file(@PathVariable long id, HttpServletResponse response) {
        generations.download(Actor.user(permit(READ_FILE)), id, (metadata, input) -> {
            setFile(response, metadata.downloadName(), metadata.sizeBytes());
            input.transferTo(response.getOutputStream());
        });
    }

    @ExceptionHandler(DocGenerationFailure.class)
    public ResponseEntity<R<FailureDetails>> error(DocGenerationFailure failure) {
        var result = new R<FailureDetails>().setCode(failure.status().value()).setMessage(failure.getMessage())
                .setData(new FailureDetails(failure.code(), failure.path()));
        var response = ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore());
        if (failure.currentEtag() != null) response.eTag(failure.currentEtag());
        return response.body(result);
    }

    private ResponseEntity<R<VersionView>> response(VersionView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(view.etag()).body(R.ok(view));
    }

    private void setFile(HttpServletResponse response, String fileName, long size) {
        response.setContentType(DOCX_TYPE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(fileName));
        response.setContentLengthLong(size);
    }

    private String attachment(String name) {
        return ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString();
    }

    private int permit(String permission) {
        StpUtil.checkLogin();
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) StpUtil.checkPermission(permission);
        return StpUtil.getLoginIdAsInt();
    }

    record FailureDetails(String errorCode, String path) {
    }
}
