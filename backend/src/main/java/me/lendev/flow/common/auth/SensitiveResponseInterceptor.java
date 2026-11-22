package me.lendev.flow.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 敏感响应拦截器：统一为响应添加 no-store 缓存头。 需注册在鉴权拦截器之前执行，确保错误响应和令牌响应同样不被缓存。
 */
@Component
public class SensitiveResponseInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return true;
    }
}
