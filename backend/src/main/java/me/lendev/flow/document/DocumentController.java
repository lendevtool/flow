package me.lendev.flow.document;

import static me.lendev.flow.common.auth.PermissionCode.*;
import static me.lendev.flow.document.DocumentCenter.*;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.R;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 「业务信息 → 文档中心」独立文档的 HTTP 入口。
 */
@RestController
@RequestMapping(DocumentController.ROOT_PATH)
@RequiredArgsConstructor
public class DocumentController {

    static final String ROOT_PATH = "/documents";

    private final DocumentCenter documents;
    private final MessageSource messages;

    /**
     * 将无法解析的文档信息映射为本地化错误响应。
     *
     * @param locale 请求协商后的语言
     * @return HTTP 400 和文档信息错误提示
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> invalidDocumentInfo(Locale locale) {
        return ResponseEntity.badRequest().header(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag())
                .varyBy(HttpHeaders.ACCEPT_LANGUAGE)
                .body(new R<Void>().setCode(400).setMessage(
                        messages.getMessage("error.document.upload.details", null, locale)));
    }

    /**
     * 分页查询当前登录人创建的独立文档。
     *
     * @param pageNo       页码，从 1 开始，省略时为 1
     * @param pageSize     单页数量，范围为 1 至 100，省略时为 20
     * @param keyword      可选文档名称关键词
     * @param departmentId 可选部门 ID
     * @param productId    可选产品 ID
     * @param parameters   查询参数集合，用于读取第 1 至 6 个维度的可选筛选值
     * @return 授权范围内的当前页文档和总数
     */
    @GetMapping
    public R<Page> list(@RequestParam(required = false) Integer pageNo, @RequestParam(required = false) Integer pageSize,
                        @RequestParam(required = false) String keyword, @RequestParam(required = false) Long departmentId,
                        @RequestParam(required = false) Long productId, @RequestParam MultiValueMap<String, String> parameters) {
        DocumentAccess.permit(DOCUMENT_CENTER_VIEW);
        var values = new LinkedHashMap<Integer, String>();
        for (int slot = 1; slot <= 6; slot++) {
            String value = parameters.getFirst("dimension" + slot + "Value");
            if (value != null && !value.isBlank()) {
                values.put(slot, value);
            }
        }
        return R.ok(documents.list(null, new Filter(pageNo, pageSize, keyword, departmentId, productId, values)));
    }

    /**
     * 查看独立文档及已发布的版本历史。
     *
     * @param id 当前登录人拥有的文档 ID
     * @return 文档属性、最新版本及已发布的版本历史
     */
    @GetMapping("/{id}")
    public R<Document> detail(@PathVariable long id) {
        DocumentAccess.permit(DOCUMENT_CENTER_VIEW);
        return R.ok(documents.detail(null, id));
    }

    /**
     * 从 multipart 请求创建独立文档并发布首个文件版本。
     *
     * @param metadata 文档属性，包括名称和可选维度
     * @param file     要上传的文件，缺少时由业务校验拒绝
     * @return 创建完成后的文档详情
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Document> create(@RequestPart(required = false) Metadata metadata,
                              @RequestPart(required = false) MultipartFile file) {
        permit(DOCUMENT_CENTER_UPLOAD);
        var version = upload(null, metadata, file);
        return R.ok(documents.detail(null, version.documentId()));
    }

    /**
     * 替换独立文档的属性，未填写的可选属性被清空。
     *
     * @param id       当前登录人拥有的文档 ID
     * @param metadata 修改后的完整文档属性
     * @return 更新后的文档详情
     */
    @PutMapping("/{id}")
    public R<Document> update(@PathVariable long id, @RequestBody Metadata metadata) {
        permit(DOCUMENT_CENTER_EDIT);
        return R.ok(documents.update(null, id, metadata));
    }

    /**
     * 向独立文档上传并发布不可变的新版本。
     *
     * @param id   当前登录人拥有的文档 ID
     * @param file 要发布的文件，缺少时由业务校验拒绝
     * @return 发布后的版本信息和下载名称
     */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Version> newVersion(@PathVariable long id, @RequestPart(required = false) MultipartFile file) {
        permit(DOCUMENT_CENTER_UPLOAD);
        return R.ok(upload(id, null, file));
    }

    /**
     * 流式写出文档版本并设置附件下载元数据。
     *
     * @param id        当前登录人拥有的文档 ID
     * @param versionId 可选历史版本 ID，省略时取最新可下载版本
     * @param response  下载内容、长度和附件名称写入的 HTTP 响应
     * @throws IOException 响应已提交后传输失败
     */
    @GetMapping("/{id}/download")
    public void download(@PathVariable long id, @RequestParam(required = false) Long versionId,
                         HttpServletResponse response) throws IOException {
        permit(DOCUMENT_CENTER_DOWNLOAD);
        try {
            documents.download(null, id, versionId, (version, stream) -> {
                response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
                response.setContentLengthLong(version.sizeBytes());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(version.downloadName(), StandardCharsets.UTF_8).build().toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                writeDownloadContent(stream, response);
            });
        } catch (DocumentFailure failure) {
            if (response.isCommitted()) {
                throw new IOException("下载传输未完成", failure);
            }
            response.resetBuffer();
            response.setContentType(null);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
            response.setHeader(HttpHeaders.CONTENT_LENGTH, null);
            throw failure;
        }
    }

    private static void writeDownloadContent(InputStream source, HttpServletResponse response) throws IOException {
        OutputStream target;
        try {
            target = response.getOutputStream();
        } catch (IOException failure) {
            throw new DownloadOutputException(failure);
        }
        byte[] buffer = new byte[8192];
        for (int count; (count = source.read(buffer)) != -1; ) {
            try {
                target.write(buffer, 0, count);
            } catch (IOException failure) {
                throw new DownloadOutputException(failure);
            }
        }
        try {
            response.flushBuffer();
        } catch (IOException failure) {
            throw new DownloadOutputException(failure);
        }
    }

    /**
     * 逻辑删除独立文档并调度版本文件清理。
     *
     * @param id 当前登录人拥有的文档 ID
     * @return 空业务数据的成功响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        permit(DOCUMENT_CENTER_DELETE);
        documents.delete(id);
        return R.ok();
    }

    private Version upload(Long id, Metadata metadata, MultipartFile file) {
        if (file == null) {
            throw BadRequestException.localized("error.document.upload.select-file");
        }
        try (var stream = file.getInputStream()) {
            return documents.upload(null, id, metadata,
                    new Upload(file.getOriginalFilename(), file.getContentType(), file.getSize(), stream));
        } catch (IOException invalidFile) {
            throw BadRequestException.localized("error.document.file");
        }
    }

    private static void permit(String action) {
        DocumentAccess.permit(DOCUMENT_CENTER_VIEW);
        DocumentAccess.permit(action);
    }
}
