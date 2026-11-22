package me.lendev.flow.common.exception;

import lombok.Getter;
import me.lendev.flow.common.i18n.ApiMessage;

/**
 * 参数或业务校验失败，由 HTTP 层返回 400。
 */
@Getter
public class BadRequestException extends RuntimeException {

    private final transient ApiMessage apiMessage;

    public BadRequestException(String message) {
        super(message);
        this.apiMessage = null;
    }

    private BadRequestException(ApiMessage apiMessage) {
        super(apiMessage.key());
        this.apiMessage = apiMessage;
    }

    /**
     * 创建待翻译的校验异常。
     *
     * @param key       消息资源键
     * @param arguments 用于填充消息占位符的参数
     * @return 参数校验异常
     */
    public static BadRequestException localized(String key, Object... arguments) {
        return new BadRequestException(new ApiMessage(key, arguments));
    }
}
