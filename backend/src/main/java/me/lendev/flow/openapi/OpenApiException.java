package me.lendev.flow.openapi;

/**
 * 开放 API 的稳定错误码和 HTTP 元数据。
 */
public class OpenApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final String challenge;
    private final Long retryAfter;

    public OpenApiException(int status, String code, String message) {
        this(status, code, message, null, null);
    }

    private OpenApiException(int status, String code, String message, String challenge, Long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.challenge = challenge;
        this.retryAfter = retryAfter;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }

    String challenge() {
        return challenge;
    }

    Long retryAfter() {
        return retryAfter;
    }

    static OpenApiException client() {
        return new OpenApiException(401, "invalid_client", "Invalid client credentials", "Basic realm=\"Flow Open API\"", null);
    }

    static OpenApiException bearer(boolean present) {
        return present
                ? new OpenApiException(401, "INVALID_TOKEN", "Invalid access token", "Bearer error=\"invalid_token\"", null)
                : new OpenApiException(401, "INVALID_TOKEN", null, "Bearer", null);
    }

    static OpenApiException scope(String required) {
        return new OpenApiException(403, "INSUFFICIENT_SCOPE", "Required scope is missing",
                "Bearer error=\"insufficient_scope\", scope=\"" + required + "\"", null);
    }

    static OpenApiException unavailableEndpoint() {
        return new OpenApiException(404, "ENDPOINT_UNAVAILABLE", "Endpoint is unavailable");
    }

    static OpenApiException unavailableDependency() {
        return new OpenApiException(503, "DEPENDENCY_UNAVAILABLE", "Service temporarily unavailable");
    }

    static OpenApiException limited(long seconds) {
        return new OpenApiException(429, "RATE_LIMITED", "Rate limit exceeded", null, seconds);
    }
}
