package me.lendev.flow.common.auth.rate;

/**
 * 请求过于频繁异常，携带固定窗口剩余等待秒数，用于填充 HTTP Retry-After 响应头
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("请求过于频繁，请稍后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
