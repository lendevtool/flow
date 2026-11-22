package me.lendev.flow.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditResultMismatchException;
import me.lendev.flow.audit.AuditUnavailableException;
import me.lendev.flow.common.auth.rate.TooManyRequestsException;
import me.lendev.flow.common.i18n.ApiMessage;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.common.response.R;
import me.lendev.flow.workbench.flow.WorkflowPrimaryHandlerException;
import me.lendev.flow.workbench.flow.WorkflowPrimaryHandlerReport;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 将应用异常转换为现有的响应结构，并按 MVC 解析的请求语言生成提示。
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ExceptionHandlerConfigure {

    private final MessageSource messages;

    @ExceptionHandler(AuditUnavailableException.class)
    public ResponseEntity<R<Void>> auditUnavailable(Locale locale) {
        return serviceUnavailable("error.audit.unavailable", locale);
    }

    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(AuditResultMismatchException exception, Locale locale) {
        String requestId = exception.requestId();
        log.error("流程审计配置异常，requestId={}", requestId, exception);
        String text = requestId == null ? message("error.audit.result-mismatch", locale)
                : message(new ApiMessage("error.audit.result-mismatch.reference", requestId), locale);
        return localized(HttpStatus.INTERNAL_SERVER_ERROR, locale).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body(500, text));
    }

    /**
     * 在错误转发触发二次鉴权前返回 Redis 故障，保留 503 和禁用缓存的响应契约。
     *
     * @param exception Redis 连接异常，用于记录故障原因
     * @param locale    请求协商后的语言
     * @param response  当前 HTTP 响应，用于关联已生成的请求 ID
     * @return 可直接序列化的 503 响应
     */
    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(RedisConnectionFailureException exception, Locale locale, HttpServletResponse response) {
        log.warn("Redis 连接失败，请求返回 503，请检查配置地址、网络连通性、代理及 Redis 服务状态，requestId={} errorType={} causeType={}",
                response.getHeader("X-Request-ID"), exception.getClass().getSimpleName(),
                exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
        return serviceUnavailable("error.redis.unavailable", locale);
    }

    @ExceptionHandler(me.lendev.flow.common.auth.mfa.MfaUnavailableException.class)
    public ResponseEntity<R<Void>> mfaUnavailable(Locale locale) {
        return serviceUnavailable("error.mfa.unavailable", locale);
    }

    @ExceptionHandler(me.lendev.flow.common.auth.domain.DomainUnavailableException.class)
    public ResponseEntity<R<Void>> domainUnavailable(Locale locale) {
        return serviceUnavailable("error.domain.unavailable", locale);
    }

    @ExceptionHandler(me.lendev.flow.pms.department.MembershipConflictException.class)
    public ResponseEntity<R<Void>> membershipConflict(me.lendev.flow.pms.department.MembershipConflictException exception,
                                                      Locale locale) {
        return response(HttpStatus.CONFLICT, 409, message(exception.getMessage(), locale), locale);
    }

    @ExceptionHandler(me.lendev.flow.policy.PolicyConflictException.class)
    public ResponseEntity<R<Void>> policyConflict(Locale locale) {
        return response(HttpStatus.CONFLICT, 409, message("error.policy.conflict", locale), locale);
    }

    @ExceptionHandler(me.lendev.flow.calendar.TradingCalendarConflictException.class)
    public ResponseEntity<R<Void>> tradingCalendarConflict(Locale locale) {
        return response(HttpStatus.CONFLICT, 409, message("error.trading-calendar.conflict", locale), locale);
    }

    @ExceptionHandler(me.lendev.flow.workbench.todo.TodoConflictException.class)
    public ResponseEntity<R<Void>> todoConflict(me.lendev.flow.workbench.todo.TodoConflictException exception,
                                                Locale locale) {
        return response(HttpStatus.CONFLICT, 409, message(exception.getMessage(), locale), locale);
    }

    @ExceptionHandler(me.lendev.flow.workbench.todo.TodoNotFoundException.class)
    public ResponseEntity<R<Void>> todoNotFound(Locale locale) {
        return response(HttpStatus.NOT_FOUND, 404, message("error.todo.not-found", locale), locale);
    }

    @ExceptionHandler(me.lendev.flow.policy.PolicyUnavailableException.class)
    public ResponseEntity<R<Void>> policyUnavailable(Locale locale) {
        return serviceUnavailable("error.policy.unavailable", locale);
    }

    @ExceptionHandler(me.lendev.flow.document.DocumentFailure.class)
    public ResponseEntity<R<Void>> documentFailure(me.lendev.flow.document.DocumentFailure exception, Locale locale) {
        return response(exception.getStatus(), exception.getStatus().value(), message(exception.getMessage(), locale), locale);
    }

    @ExceptionHandler(me.lendev.flow.institution.InstitutionFailure.class)
    public ResponseEntity<R<Void>> institutionFailure(me.lendev.flow.institution.InstitutionFailure exception,
                                                      Locale locale) {
        return response(exception.getStatus(), exception.getStatus().value(), message(exception.getMessage(), locale), locale);
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> notLoggedIn(Locale locale) {
        return response(HttpStatus.UNAUTHORIZED, 401, message("error.unauthenticated", locale), locale);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<R<Void>> resourceNotFound(Locale locale) {
        return response(HttpStatus.NOT_FOUND, 404, message("error.resource.not-found", locale), locale);
    }

    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(BizException exception, Locale locale) {
        String message = exception.getApiMessage() == null
                ? exception.getMessage()
                : message(exception.getApiMessage(), locale);
        return response(HttpStatus.OK, exception.getCode().getCode(), message, locale);
    }

    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(BadRequestException exception, Locale locale) {
        String message = exception.getApiMessage() == null
                ? exception.getMessage()
                : message(exception.getApiMessage(), locale);
        return response(HttpStatus.BAD_REQUEST, 400, message, locale);
    }

    @ExceptionHandler
    public ResponseEntity<R<WorkflowPrimaryHandlerReport>> handle(WorkflowPrimaryHandlerException exception, Locale locale) {
        var report = exception.getReport();
        var issues = report.missingPrimaryHandlers().stream().map(issue -> primaryHandlerIssue(issue, locale)).toList();
        String text = message(new ApiMessage(exception.getMessage(), issues), locale);
        return localized(HttpStatus.BAD_REQUEST, locale)
                .body(new R<WorkflowPrimaryHandlerReport>().setCode(400).setMessage(text).setData(report));
    }

    private static ApiMessage primaryHandlerIssue(WorkflowPrimaryHandlerReport.Issue issue, Locale locale) {
        String name = Locale.ENGLISH.getLanguage().equals(locale.getLanguage()) && issue.roleNameEn() != null
                && !issue.roleNameEn().isBlank() ? issue.roleNameEn() : issue.roleName();
        String role = name == null ? issue.roleCode() : name + " (" + issue.roleCode() + ")";
        return switch (issue.reason()) {
            case ROLE_REQUIRED -> new ApiMessage("error.workflow.primary-handler.role-required", issue.nodeTitle());
            case ROLE_NOT_FOUND ->
                    new ApiMessage("error.workflow.primary-handler.role-not-found", issue.nodeTitle(), role);
            case ROLE_DISABLED ->
                    new ApiMessage("error.workflow.primary-handler.role-disabled", issue.nodeTitle(), role);
            case PRIMARY_UNAVAILABLE ->
                    new ApiMessage("error.workflow.primary-handler.unavailable", issue.nodeTitle(), role);
        };
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<R<Void>> accessDenied(Locale locale) {
        return handle(new BizException(BizResponseCode.ERR_11003), locale);
    }

    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(MethodArgumentNotValidException exception, Locale locale) {
        var error = exception.getBindingResult().getAllErrors().getFirst();
        String message = error instanceof FieldError field && field.isBindingFailure()
                ? message("error.request.invalid", locale)
                : error.getDefaultMessage();
        return response(HttpStatus.OK, 400, message, locale);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<R<Void>> invalidMethodArgument(Locale locale) {
        return response(HttpStatus.OK, 400, message("error.request.validation", locale), locale);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> unreadableBody(Locale locale) {
        return response(HttpStatus.BAD_REQUEST, 400, message("error.request.unreadable", locale), locale);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> uploadTooLarge(Locale locale) {
        return response(HttpStatus.CONTENT_TOO_LARGE, 413, message("error.request.upload.too-large", locale), locale);
    }

    @ExceptionHandler({TypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<R<Void>> invalidRequestParameter(Locale locale) {
        return response(HttpStatus.BAD_REQUEST, 400, message("error.request.invalid", locale), locale);
    }

    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(TooManyRequestsException exception, Locale locale) {
        return localized(HttpStatus.TOO_MANY_REQUESTS, locale)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(body(429, message("error.rate-limited", locale)));
    }

    @ExceptionHandler
    public ResponseEntity<R<Void>> handle(DataIntegrityViolationException exception, Locale locale) {
        log.debug("数据完整性约束拒绝写入", exception);
        return response(HttpStatus.BAD_REQUEST, 400, message("error.data.conflict", locale), locale);
    }

    private String message(ApiMessage message, Locale locale) {
        Object[] arguments = Arrays.stream(message.arguments()).map(argument -> {
            if (argument instanceof List<?> values && values.stream().allMatch(ApiMessage.class::isInstance)) {
                return values.stream().map(ApiMessage.class::cast).map(value -> message(value, locale))
                        .collect(Collectors.joining(messages.getMessage("message.list-separator", null, locale)));
            }
            return argument;
        }).toArray();
        String text = messages.getMessage(message.key(), arguments, null, locale);
        if (text == null) {
            log.warn("接口提示翻译缺失，已使用通用提示，请核对对应语言资源文件，messageKey={} locale={}",
                    message.key(), locale.toLanguageTag());
            return messages.getMessage("error.unexpected", null, locale);
        }
        return text;
    }

    private String message(String key, Locale locale) {
        return message(new ApiMessage(key), locale);
    }

    private ResponseEntity<R<Void>> serviceUnavailable(String key, Locale locale) {
        return localized(HttpStatus.SERVICE_UNAVAILABLE, locale)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body(503, message(key, locale)));
    }

    private static ResponseEntity<R<Void>> response(HttpStatus status, int code, String message, Locale locale) {
        return localized(status, locale).body(body(code, message));
    }

    private static ResponseEntity.BodyBuilder localized(HttpStatus status, Locale locale) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag())
                .varyBy(HttpHeaders.ACCEPT_LANGUAGE);
    }

    private static R<Void> body(int code, String message) {
        return new R<Void>().setCode(code).setMessage(message);
    }
}
