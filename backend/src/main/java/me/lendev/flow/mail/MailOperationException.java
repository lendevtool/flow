package me.lendev.flow.mail;

import me.lendev.flow.common.response.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 邮件业务操作失败，携带 HTTP 状态、可向调用方展示的提示和不含邮件内容的稳定错误码。
 */
public final class MailOperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    MailOperationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 返回发送调用的失败类别，供内部调用判断是否重试。
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 返回不含模板、地址或服务商响应内容的错误码。
     */
    public String code() {
        return code;
    }
}

@Order(Ordered.HIGHEST_PRECEDENCE)
/**
 * 将邮件业务拒绝映射为 HTTP 响应，并在响应头返回稳定的邮件错误码。
 */
@RestControllerAdvice(basePackages = "me.lendev.flow.mail")
class MailOperationAdvice {
    @ExceptionHandler(MailOperationException.class)
    ResponseEntity<R<Void>> handle(MailOperationException failure) {
        var body = new R<Void>().setCode(failure.status().value()).setMessage(failure.getMessage());
        return ResponseEntity.status(failure.status()).header("X-Mail-Error-Code", failure.code()).body(body);
    }
}
