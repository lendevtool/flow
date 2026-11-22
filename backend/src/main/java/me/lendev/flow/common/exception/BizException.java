package me.lendev.flow.common.exception;

import lombok.Getter;
import me.lendev.flow.common.i18n.ApiMessage;
import me.lendev.flow.common.response.BizResponseCode;

/**
 * 业务异常，这种异常一般是可预知的
 *
 * @author lendev
 */
@Getter
public class BizException extends RuntimeException {

    private final BizResponseCode code;
    private final transient ApiMessage apiMessage;

    public BizException(BizResponseCode code) {
        this(code, code.getMsg(), new ApiMessage("error." + code.getCode()));
    }

    public BizException(BizResponseCode code, String message) {
        this(code, message, null);
    }

    private BizException(BizResponseCode code, String message, ApiMessage apiMessage) {
        super(message);
        this.code = code;
        this.apiMessage = apiMessage;
    }

    /**
     * 为现有业务错误码提供更具体的提示。
     *
     * @param code      对外业务错误码
     * @param key       消息资源键
     * @param arguments 用于填充消息占位符的参数
     * @return 业务异常
     */
    public static BizException localized(BizResponseCode code, String key, Object... arguments) {
        return new BizException(code, code.getMsg(), new ApiMessage(key, arguments));
    }
}
