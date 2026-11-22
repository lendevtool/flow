package me.lendev.flow.openapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.ResourceNotFoundException;
import me.lendev.flow.institution.InstitutionFailure;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

/**
 * 为开放业务路由接入应用鉴权和独立的错误响应。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenApiProperties.class)
@RequiredArgsConstructor
class OpenApiWeb implements WebMvcConfigurer {
    private final OpenApiAuthorization authorization;
    private final OpenApiRegistry registry;
    private final OpenApiResponses responses;

    @Override
    public void addInterceptors(InterceptorRegistry interceptors) {
        interceptors.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var call = OpenApiTrafficFilter.call(request);
                if (call == null || !(handler instanceof HandlerMethod method)) {
                    throw OpenApiException.unavailableEndpoint();
                }
                if (method.getBeanType().equals(ManagedReadController.class)) {
                    return true;
                }
                var entry = registry.resolve(method, request.getMethod(),
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
                var token = authorization.authenticate(OpenApiTrafficFilter.authorizationHeader(request));
                call.application(token.appId(), token.clientId());
                authorization.authorize(token, entry, call);
                OpenApiRequest.establish(request, token.appId(), token.clientId());
                return true;
            }
        }).addPathPatterns("/open/v1/**");
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.addFirst((request, response, handler, exception) -> resolve(request, response, exception));
    }

    private ModelAndView resolve(HttpServletRequest request, HttpServletResponse response, Exception exception) {
        var route = OpenApiTrafficFilter.route(request);
        if (route == null || response.isCommitted()) {
            return null;
        }
        boolean tokenRoute = route == OpenApiTrafficFilter.Route.TOKEN;
        var failure = describe(exception, tokenRoute);
        if (exception instanceof HttpRequestMethodNotSupportedException method) {
            method.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        }
        var call = OpenApiTrafficFilter.call(request);
        if (call != null) {
            call.failed(failure.code());
        }
        if (failure.status() >= 500) {
            log.warn("开放 API 请求返回服务端错误，请按请求 ID 检查接口处理和数据库、Redis 等依赖，requestId={} route={} appId={} endpointCode={} httpStatus={} errorCode={} errorType={}",
                    response.getHeader("X-Request-ID"), route, call == null ? null : call.appId(),
                    call == null ? null : call.endpointCode(), failure.status(), failure.code(),
                    exception.getClass().getSimpleName());
        }
        try {
            responses.failure(request, response, tokenRoute, failure);
        } catch (IOException failureWritingResponse) {
            throw new UncheckedIOException(failureWritingResponse);
        }
        return new ModelAndView();
    }

    private static OpenApiException describe(Exception exception, boolean tokenRoute) {
        if (exception instanceof OpenApiException failure) {
            return failure;
        }
        if (exception instanceof DataAccessException || exception instanceof TransactionException || exception instanceof JacksonException) {
            return OpenApiException.unavailableDependency();
        }
        if (exception instanceof NoHandlerFoundException || exception instanceof NoResourceFoundException) {
            return OpenApiException.unavailableEndpoint();
        }
        if (!tokenRoute) {
            var sourceFailure = describeSourceFailure(exception);
            if (sourceFailure != null) {
                return sourceFailure;
            }
        }
        return describeRequestFailure(exception, tokenRoute);
    }

    private static OpenApiException describeRequestFailure(Exception exception, boolean tokenRoute) {
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            return new OpenApiException(405, "METHOD_NOT_ALLOWED", "Method is not allowed");
        }
        if (exception instanceof HttpMediaTypeNotAcceptableException) {
            return new OpenApiException(406, tokenRoute ? "invalid_request" : "NOT_ACCEPTABLE", "Only JSON responses are available");
        }
        if (exception instanceof ServletRequestBindingException || exception instanceof TypeMismatchException
                || exception instanceof MethodArgumentNotValidException || exception instanceof HttpMessageNotReadableException
                || exception instanceof HandlerMethodValidationException || exception instanceof ConstraintViolationException
                || exception instanceof HttpMediaTypeNotSupportedException) {
            return new OpenApiException(400, tokenRoute ? "invalid_request" : "INVALID_ARGUMENT", "Invalid request parameters");
        }
        return new OpenApiException(500, "INTERNAL_ERROR", "Internal server error");
    }

    private static OpenApiException describeSourceFailure(Exception exception) {
        if (exception instanceof InstitutionFailure failure && failure.getStatus() == HttpStatus.NOT_FOUND) {
            return new OpenApiException(404, "RESOURCE_NOT_FOUND", "Institution was not found");
        }
        if (exception instanceof ResourceNotFoundException) {
            return new OpenApiException(404, "RESOURCE_NOT_FOUND", "Resource was not found");
        }
        if (exception instanceof BadRequestException) {
            return new OpenApiException(400, "INVALID_ARGUMENT", "Invalid source query");
        }
        return null;
    }
}
