package me.lendev.flow.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 设置开放路径的追踪、禁用缓存和协议错误响应。
 */
@Component
@RequiredArgsConstructor
class OpenApiResponses {
    private static final String REQUEST_ID = OpenApiResponses.class.getName() + ".requestId";
    private final ObjectMapper json;

    static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_ID);
    }

    static String prepare(HttpServletRequest request, HttpServletResponse response, boolean tokenRoute) {
        String requestId = requestId(request);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID, requestId);
        }
        response.setHeader("X-Request-ID", requestId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (tokenRoute) {
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        }
        return requestId;
    }

    void failure(HttpServletRequest request, HttpServletResponse response, boolean tokenRoute, OpenApiException failure)
            throws IOException {
        String requestId = prepare(request, response, tokenRoute);
        response.setStatus(failure.status());
        if (failure.challenge() != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, failure.challenge());
        }
        if (failure.retryAfter() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, failure.retryAfter().toString());
        }
        if (failure.getMessage() != null && !"HEAD".equals(request.getMethod())) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Object body = tokenRoute ? new OAuthError(oauthCode(failure), failure.getMessage())
                    : new BusinessError(failure.code(), failure.getMessage(), requestId);
            json.writeValue(response.getOutputStream(), body);
        }
    }

    private static String oauthCode(OpenApiException failure) {
        return switch (failure.code()) {
            case "DEPENDENCY_UNAVAILABLE", "INTERNAL_ERROR" -> "temporarily_unavailable";
            case "RATE_LIMITED" -> "rate_limited";
            case "ENDPOINT_UNAVAILABLE" -> "endpoint_unavailable";
            default -> failure.code();
        };
    }

    private record OAuthError(String error, @JsonProperty("error_description") String description) {
    }

    private record BusinessError(String code, String message, String requestId) {
    }
}
