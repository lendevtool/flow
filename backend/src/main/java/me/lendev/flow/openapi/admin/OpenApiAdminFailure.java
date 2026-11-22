package me.lendev.flow.openapi.admin;

import static org.springframework.http.HttpStatus.*;

import me.lendev.flow.common.response.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 后台操作的业务拒绝，仅携带固定错误码和可展示文案。
 */
final class OpenApiAdminFailure extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private OpenApiAdminFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static OpenApiAdminFailure invalid(String message) {
        return new OpenApiAdminFailure(BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    static OpenApiAdminFailure denied() {
        return new OpenApiAdminFailure(FORBIDDEN, "ACCESS_DENIED", "没有开放 API 管理权限");
    }

    static OpenApiAdminFailure notFound() {
        return new OpenApiAdminFailure(NOT_FOUND, "RESOURCE_NOT_FOUND", "开放 API 资源不存在");
    }

    static OpenApiAdminFailure versionConflict() {
        return new OpenApiAdminFailure(CONFLICT, "VERSION_CONFLICT", "配置已变更，请刷新后重试");
    }

    static OpenApiAdminFailure registrationMismatch() {
        return new OpenApiAdminFailure(CONFLICT, "ENDPOINT_REGISTRATION_MISMATCH", "接口目录与部署登记不一致");
    }

    static OpenApiAdminFailure endpointConflict() {
        return new OpenApiAdminFailure(CONFLICT, "ENDPOINT_CONFLICT", "对外路径或 Scope 已存在");
    }

    static OpenApiAdminFailure rotationInProgress() {
        return new OpenApiAdminFailure(CONFLICT, "SECRET_ROTATION_IN_PROGRESS", "应用已有进行中的凭据轮转");
    }

    static OpenApiAdminFailure secretStateConflict() {
        return new OpenApiAdminFailure(CONFLICT, "SECRET_STATE_CONFLICT", "凭据已过期或状态已变更，请刷新后重试");
    }

    static OpenApiAdminFailure docsUnavailable() {
        return new OpenApiAdminFailure(SERVICE_UNAVAILABLE, "DOCUMENTATION_UNAVAILABLE", "接口文档暂不可用");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}

/**
 * 将开放 API 后台的业务拒绝和校验失败映射为管理端响应。
 */
@RestControllerAdvice(assignableTypes = {OpenApiAdminController.class, OpenApiCredentialController.class,
        OpenApiAdminDocumentsController.class, OpenApiSourceAdminController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class OpenApiAdminAdvice {

    @ExceptionHandler(OpenApiAdminFailure.class)
    ResponseEntity<R<Detail>> failure(OpenApiAdminFailure failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(new R<Detail>().setCode(failure.status().value()).setMessage(failure.getMessage())
                        .setData(new Detail(failure.code())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    ResponseEntity<R<Detail>> invalid() {
        return failure(OpenApiAdminFailure.invalid("开放 API 请求参数无效"));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<R<Detail>> lockConflict() {
        return failure(OpenApiAdminFailure.versionConflict());
    }

    record Detail(String errorCode) {
    }
}
