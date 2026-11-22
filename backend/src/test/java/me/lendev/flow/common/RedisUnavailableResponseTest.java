package me.lendev.flow.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.NoRouteToHostException;
import java.util.concurrent.atomic.AtomicBoolean;

import me.lendev.flow.common.exception.ExceptionHandlerConfigure;
import me.lendev.flow.common.i18n.ApiLocaleConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Redis 不可用时的响应契约：基础设施故障必须直接返回 503 JSON， 不得转发到 /error 导致 Sa-Token 鉴权被重复执行或受保护资源被泄露。
 */
class RedisUnavailableResponseTest {

    private static ExceptionHandlerConfigure exceptionHandler() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return new ExceptionHandlerConfigure(messages);
    }

    private static RedisConnectionFailureException offline() {
        return new RedisConnectionFailureException("Unable to connect to Redis", new NoRouteToHostException("No route to host"));
    }

    @RestController
    static class ProbeController {

        final AtomicBoolean protectedInvoked = new AtomicBoolean();

        @GetMapping("/auth/captcha")
        String captcha() {
            throw offline();
        }

        @GetMapping("/protected")
        String protectedResource() {
            protectedInvoked.set(true);
            return "must not be disclosed";
        }
    }

    @Test
    void controllerRedisFailureReturnsServiceUnavailableJson() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(exceptionHandler())
                .setLocaleResolver(new ApiLocaleConfiguration().localeResolver())
                .build();
        mvc.perform(get("/auth/captcha"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("Redis服务暂不可用，请检查连接后重试"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void authenticationRedisFailureKeepsProtectedControllerUnreachable() throws Exception {
        ProbeController controller = new ProbeController();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler())
                .setLocaleResolver(new ApiLocaleConfiguration().localeResolver())
                // 模拟鉴权阶段（拦截器）发生 Redis 故障：响应不得落入 /error 转发，
                // 且受保护的 Controller 方法绝不能被执行。
                .addInterceptors(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        throw offline();
                    }
                }).build();
        mvc.perform(get("/protected").header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("Redis is temporarily unavailable. Check the connection and try again."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(forwardedUrl(null));
        assertFalse(controller.protectedInvoked.get());
    }
}
