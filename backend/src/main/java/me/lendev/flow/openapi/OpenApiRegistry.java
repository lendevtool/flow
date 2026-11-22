package me.lendev.flow.openapi;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 从实际 MVC 映射读取可开放方法，并核对数据库目录中的接口契约。
 */
@Component
public class OpenApiRegistry implements SmartInitializingSingleton {
    private static final Pattern PATH = Pattern.compile("/open/(v[1-9]\\d*)/.*");
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private final ObjectProvider<RequestMappingHandlerMapping> mapping;
    private Map<String, Registration> registrations = Map.of();

    public OpenApiRegistry(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping) {
        this.mapping = mapping;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Registration> entries = new HashMap<>();
        mapping.getObject().getHandlerMethods().forEach((info, handler) -> {
            var endpoint = handler.getMethodAnnotation(OpenApiEndpoint.class);
            if (endpoint == null) {
                return;
            }
            var paths = info.getPatternValues();
            var methods = info.getMethodsCondition().getMethods();
            if (paths.size() != 1 || methods.size() != 1 || !CODE.matcher(endpoint.value()).matches()) {
                throw new IllegalStateException("开放接口必须具有唯一的路径、方法和有效编码: " + endpoint.value());
            }
            String path = paths.iterator().next();
            var version = PATH.matcher(path);
            if (!version.matches()) {
                throw new IllegalStateException("开放接口路径缺少版本: " + endpoint.value());
            }
            var entry = new Registration(endpoint.value(), handler.getMethod(), methods.iterator().next().name(),
                    path, version.group(1), endpoint.dataScopeType());
            if (entries.putIfAbsent(entry.code(), entry) != null) {
                throw new IllegalStateException("重复的开放接口编码: " + entry.code());
            }
        });
        registrations = Map.copyOf(entries);
    }

    /**
     * 核对已部署 Controller 的方法、路径和范围声明。
     */
    public boolean matches(String code, String method, String path, String version, String dataScopeType) {
        var entry = registrations.get(code);
        return entry != null && entry.method().equals(method) && entry.path().equals(path)
                && entry.version().equals(version) && entry.dataScopeType().equals(dataScopeType);
    }

    Registration resolve(HandlerMethod handler, String method, Object path) {
        var endpoint = handler.getMethodAnnotation(OpenApiEndpoint.class);
        var entry = endpoint == null ? null : registrations.get(endpoint.value());
        if (entry == null || !entry.handler().equals(handler.getMethod()) || !entry.method().equals(method)
                || path == null || !entry.path().equals(path.toString())) {
            throw OpenApiException.unavailableEndpoint();
        }
        return entry;
    }

    record Registration(String code, Method handler, String method, String path, String version, String dataScopeType) {
    }
}
