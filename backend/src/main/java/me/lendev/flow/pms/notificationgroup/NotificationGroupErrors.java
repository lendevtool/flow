package me.lendev.flow.pms.notificationgroup;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.notificationgroup.NotificationGroupFailure.ConflictReason;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 将通知群组的业务错误和接口校验统一映射为本地化 HTTP 响应。
 */
@RestControllerAdvice(assignableTypes = NotificationGroupController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class NotificationGroupErrors {

    private final MessageSource messages;

    /**
     * 返回业务状态与稳定的冲突原因，供界面引导刷新或解绑。
     */
    @ExceptionHandler(NotificationGroupFailure.class)
    public ResponseEntity<R<Conflict>> failure(NotificationGroupFailure failure, Locale locale) {
        Conflict detail = failure.reason() == null ? null : new Conflict(failure.reason(), failure.usageCount());
        return ResponseEntity.status(failure.status()).header(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag())
                .varyBy(HttpHeaders.ACCEPT_LANGUAGE).body(new R<Conflict>().setCode(failure.status().value())
                        .setMessage(messages.getMessage(failure.getMessage(), null, locale)).setData(detail));
    }

    /**
     * 请求体、分页参数或路径格式错误时，返回 HTTP 400。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    public ResponseEntity<R<Conflict>> invalid(Locale locale) {
        return failure(NotificationGroupFailure.invalidInput(), locale);
    }

    /**
     * 死锁或锁等待超时回滚后，请求刷新数据并重试。
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<R<Conflict>> lockConflict(Locale locale) {
        return failure(NotificationGroupFailure.versionConflict(), locale);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Conflict(ConflictReason reason, Long usageCount) {

    }
}
