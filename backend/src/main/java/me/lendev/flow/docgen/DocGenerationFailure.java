package me.lendev.flow.docgen;

import org.springframework.http.HttpStatus;

/**
 * 生成模块稳定错误。服务异常与 HTTP 层共享业务码。
 */
public final class DocGenerationFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String path;
    private final String currentEtag;

    public DocGenerationFailure(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public DocGenerationFailure(HttpStatus status, String code, String message, String path) {
        this(status, code, message, path, null);
    }

    private DocGenerationFailure(HttpStatus status, String code, String message, String path, String currentEtag) {
        super(message);
        this.status = status;
        this.code = code;
        this.path = path;
        this.currentEtag = currentEtag;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }

    String currentEtag() {
        return currentEtag;
    }

    static DocGenerationFailure staleDraft(String etag) {
        return new DocGenerationFailure(HttpStatus.CONFLICT, "VERSION_CONFLICT", "模板版本已更新或已发布，请刷新", null, etag);
    }

    static DocGenerationFailure invalid(String message) {
        return new DocGenerationFailure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    static DocGenerationFailure conflict(String code, String message) {
        return new DocGenerationFailure(HttpStatus.CONFLICT, code, message);
    }

    static DocGenerationFailure templateMissing() {
        return new DocGenerationFailure(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "找不到文档模板或版本");
    }

    static DocGenerationFailure generationMissing() {
        return new DocGenerationFailure(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND", "找不到文档生成记录");
    }
}
