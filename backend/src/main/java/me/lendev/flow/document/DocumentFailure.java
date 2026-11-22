package me.lendev.flow.document;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 文档操作被拒绝或存储不可用。
 */
@Getter
public final class DocumentFailure extends RuntimeException {

    private final HttpStatus status;

    private DocumentFailure(HttpStatus status, String key, Throwable cause) {
        super(key, cause);
        this.status = status;
    }

    static DocumentFailure missing() {
        return new DocumentFailure(HttpStatus.NOT_FOUND, "error.document.not-found", null);
    }

    static DocumentFailure conflict(String key) {
        return new DocumentFailure(HttpStatus.CONFLICT, key, null);
    }

    static DocumentFailure storage(Throwable cause) {
        return new DocumentFailure(HttpStatus.SERVICE_UNAVAILABLE, "error.document.storage", cause);
    }

    static DocumentFailure uploadUnavailable(Throwable cause) {
        return new DocumentFailure(HttpStatus.SERVICE_UNAVAILABLE, "error.document.upload.unavailable", cause);
    }

    static DocumentFailure tooLarge() {
        return new DocumentFailure(HttpStatus.CONTENT_TOO_LARGE, "error.request.upload.too-large", null);
    }
}
