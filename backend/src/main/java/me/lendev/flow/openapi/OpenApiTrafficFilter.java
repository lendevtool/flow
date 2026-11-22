package me.lendev.flow.openapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * 在 MVC 路由前保护开放入口，并采集响应完成时的调用状态。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
@Slf4j
class OpenApiTrafficFilter extends OncePerRequestFilter {
    private static final String CALL = OpenApiTrafficFilter.class.getName() + ".call";
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{1,45}");
    private final OpenApiProperties properties;
    private final OpenApiResponses responses;
    private final OpenApiCalls calls;
    private final OpenApiRateLimits limits;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return route(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean tokenRoute = route(request) == Route.TOKEN;
        String requestId = OpenApiResponses.prepare(request, response, tokenRoute);
        if (!properties.enabled()) {
            responses.failure(request, response, tokenRoute, OpenApiException.unavailableEndpoint());
            return;
        }
        String sourceIp = resolveSourceIp(request);
        if (tokenRoute) {
            if (!HttpMethod.POST.matches(request.getMethod())) {
                response.setHeader(HttpHeaders.ALLOW, HttpMethod.POST.name());
                responses.failure(request, response, true, new OpenApiException(405, "invalid_request", "Method is not allowed"));
                return;
            }
            try {
                limits.signingIp(sourceIp);
            } catch (OpenApiException failure) {
                responses.failure(request, response, true, failure);
                return;
            } catch (DataAccessException failure) {
                log.warn("开放 API 签发限流依赖失败，本次签发返回 503，请检查 Redis 连通性和写入权限，requestId={} environment={} errorType={}",
                        requestId, properties.environment(), failure.getClass().getSimpleName());
                responses.failure(request, response, true, OpenApiException.unavailableDependency());
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        var call = new OpenApiCalls.Context(requestId, sourceIp);
        request.setAttribute(CALL, call);
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            call.failed("INTERNAL_ERROR");
            calls.finish(call, 500);
            throw exception;
        }
        calls.finish(call, response.getStatus());
    }

    static String authorizationHeader(HttpServletRequest request) {
        var values = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (!values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? "" : value;
    }

    static Route route(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if ("/oauth2/token".equals(path)) {
            return Route.TOKEN;
        }
        return "/open/v1".equals(path) || path.startsWith("/open/v1/") ? Route.BUSINESS : null;
    }

    static OpenApiCalls.Context call(HttpServletRequest request) {
        return (OpenApiCalls.Context) request.getAttribute(CALL);
    }

    private String resolveSourceIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (properties.trustedProxyAddresses().contains(peer)) {
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && IP_LITERAL.matcher(forwarded).matches()
                    && (forwarded.contains(":") || forwarded.contains("."))) {
                return forwarded;
            }
        }
        return peer;
    }

    enum Route {TOKEN, BUSINESS}
}
