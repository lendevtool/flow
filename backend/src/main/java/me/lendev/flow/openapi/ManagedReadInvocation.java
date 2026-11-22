package me.lendev.flow.openapi;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidator;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletRequestDataBinderFactory;
import org.springframework.web.servlet.support.ExtendedServletRequestDataBinder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * 在一次已绑定的同步 MVC 调用内提供应用身份。
 */
@Component
@RequiredArgsConstructor
public final class ManagedReadInvocation {
    private static final ThreadLocal<Execution> INVOCATION = new ThreadLocal<>();
    private final ObjectProvider<RequestMappingHandlerAdapter> adapters;
    private final ObjectMapper json;

    /**
     * 返回当前通用入口绑定到指定 Controller 的应用身份，普通员工请求返回空值。
     *
     * @param controllerType 经过只读策略登记的 Controller 类型
     * @param sourcePath     当前匹配的来源路由
     * @return 当前已获接口授权的应用，员工请求时为空
     * @throws OpenApiException 来源不匹配或调用身份已被读取
     */
    public static OpenApiRequest callerFor(Class<?> controllerType, String sourcePath) {
        var execution = INVOCATION.get();
        if (execution == null) {
            return null;
        }
        if (execution.moduleRead || !execution.sourcePath.equals(sourcePath)) {
            throw OpenApiException.unavailableEndpoint();
        }
        return consume(execution, controllerType);
    }

    /**
     * 由模块共用的读取权限入口接收已绑定到当前处理器的应用身份。
     *
     * @param controllerType 模块登记的 Controller 类型
     * @return 当前已获接口授权的应用，员工请求时为空
     * @throws OpenApiException 模块不匹配或身份已被消费
     */
    public static OpenApiRequest callerForModule(Class<?> controllerType) {
        var execution = INVOCATION.get();
        if (execution == null) {
            return null;
        }
        if (!execution.moduleRead) {
            throw OpenApiException.unavailableEndpoint();
        }
        return consume(execution, controllerType);
    }

    private static OpenApiRequest consume(Execution execution, Class<?> controllerType) {
        if (execution.consumed || !execution.controllerType.equals(controllerType)) {
            throw OpenApiException.unavailableEndpoint();
        }
        execution.consumed = true;
        return execution.caller;
    }

    JsonNode read(HttpServletRequest request, OpenApiAuthorization.ManagedGrant grant) throws Exception {
        var binding = grant.binding();
        var source = binding.source();
        var contract = binding.contract();
        var webRequest = bindRequest(request, contract);
        var invoker = handler(source, contract.wholeResponse());
        Object result = invoke(source.handler().getBeanType(), source.path(), grant.caller(), source.moduleRead(),
                () -> invoker.invokeForRequest(webRequest, new ModelAndViewContainer()));
        if (!(result instanceof R<?> response) || response.getCode() != 0) {
            throw invalidResponse();
        }
        if (contract.wholeResponse()) {
            JsonNode body = json.valueToTree(response.getData());
            if (!EndpointGrantResponse.matches(contract.responseSchema(), body)) {
                throw invalidResponse();
            }
            return body;
        }
        var accessors = new HashMap<String, RecordComponent>();
        for (var component : source.responseType().getRecordComponents()) {
            accessors.put(component.getName(), component);
        }
        if (contract.page()) {
            if (!(response.getData() instanceof Page<?> page) || page.getPageData() == null
                    || page.getTotal() == null || page.getTotal() < 0) {
                throw invalidResponse();
            }
            var body = json.createObjectNode();
            body.set("pageData", projectRows(page.getPageData(), binding, accessors));
            return body.put("total", page.getTotal());
        }
        if (contract.collection() && response.getData() instanceof List<?> entries) {
            return projectRows(entries, binding, accessors);
        }
        return project(response.getData(), binding, accessors);
    }

    private ArrayNode projectRows(List<?> entries, ManagedReadRegistry.Binding binding, Map<String, RecordComponent> accessors)
            throws ReflectiveOperationException {
        var body = json.createArrayNode();
        for (Object entry : entries) {
            body.add(project(entry, binding, accessors));
        }
        return body;
    }

    private InvocableHandlerMethod handler(ManagedReadRegistry.Source source, boolean wholeResponse) {
        var adapter = adapters.getObject();
        var handler = source.handler().createWithResolvedBean();
        if (wholeResponse) {
            handler = handler.createWithValidateFlags();
        }
        var invoker = new InvocableHandlerMethod(handler);
        var resolvers = new HandlerMethodArgumentResolverComposite();
        resolvers.addResolvers(adapter.getArgumentResolvers());
        invoker.setHandlerMethodArgumentResolvers(resolvers);
        WebBindingInitializer initializer = adapter.getWebBindingInitializer();
        var factory = new ServletRequestDataBinderFactory(List.of(), source.moduleRead() ? queryOnlyBinding(initializer) : initializer);
        var discoverer = new DefaultParameterNameDiscoverer();
        if (handler.shouldValidateArguments() || handler.shouldValidateReturnValue()) {
            var validator = HandlerMethodValidator.from(initializer, discoverer,
                    parameter -> parameter.hasParameterAnnotation(ModelAttribute.class) || parameter.hasParameterAnnotation(Valid.class),
                    parameter -> parameter.hasParameterAnnotation(RequestParam.class));
            if (validator == null) {
                throw OpenApiException.unavailableDependency();
            }
            invoker.setMethodValidator(validator);
            factory.setMethodValidationApplicable(handler.shouldValidateArguments());
        }
        invoker.setDataBinderFactory(factory);
        invoker.setParameterNameDiscoverer(discoverer);
        return invoker;
    }

    private static WebBindingInitializer queryOnlyBinding(WebBindingInitializer initializer) {
        return binder -> {
            if (initializer != null) {
                initializer.initBinder(binder);
            }
            binder.setDeclarativeBinding(true);
            if (binder instanceof ExtendedServletRequestDataBinder servletBinder) {
                servletBinder.addHeaderPredicate(ignored -> false);
            }
        };
    }

    private static ServletWebRequest bindRequest(HttpServletRequest request, ManagedReadRegistry.Contract contract) throws IOException {
        long length = request.getContentLengthLong();
        if (length > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null
                || (length < 0 && request.getInputStream().read() != -1)) {
            throw new OpenApiException(400, "INVALID_ARGUMENT", "A request body is not allowed");
        }
        var params = request.getParameterMap();
        if (params.values().stream().anyMatch(values -> values.length != 1)
                || params.keySet().stream().anyMatch(name -> contract.parameters().stream()
                .noneMatch(parameter -> parameter.name().equals(name)))) {
            throw new OpenApiException(400, "INVALID_ARGUMENT", "Unknown or repeated request parameters");
        }
        Map<String, String> variables = new HashMap<>();
        for (String name : contract.pathVariables()) {
            String value = request.getParameter(name);
            if (value == null || value.isBlank()) {
                throw new OpenApiException(400, "INVALID_ARGUMENT", "A required route argument is missing");
            }
            variables.put(name, value);
        }
        Map<String, String> boundVariables = Map.copyOf(variables);
        return new ServletWebRequest(new HttpServletRequestWrapper(request) {
            @Override
            public Object getAttribute(String name) {
                return HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE.equals(name) ? boundVariables : super.getAttribute(name);
            }
        });
    }

    private JsonNode project(Object value, ManagedReadRegistry.Binding binding, Map<String, RecordComponent> accessors)
            throws ReflectiveOperationException {
        if (value == null || !binding.source().responseType().equals(value.getClass())) {
            throw invalidResponse();
        }
        var body = json.createObjectNode();
        for (var field : binding.contract().fields()) {
            Object scalar = accessors.get(field.name()).getAccessor().invoke(value);
            body.set(field.name(), json.valueToTree(scalar instanceof LocalDate date ? date.toString() : scalar));
        }
        return body;
    }

    private static OpenApiException invalidResponse() {
        return new OpenApiException(500, "INTERNAL_ERROR", "Unexpected response from the source interface");
    }

    static <T> T invoke(Class<?> controllerType, String sourcePath, OpenApiRequest caller, Callable<T> operation) throws Exception {
        return invoke(controllerType, sourcePath, caller, false, operation);
    }

    static boolean moduleReadActive() {
        var execution = INVOCATION.get();
        return execution != null && execution.moduleRead;
    }

    static <T> T invoke(Class<?> controllerType, String sourcePath, OpenApiRequest caller, boolean moduleRead,
                        Callable<T> operation) throws Exception {
        if (INVOCATION.get() != null) {
            throw OpenApiException.unavailableEndpoint();
        }
        var execution = new Execution(controllerType, sourcePath, caller, moduleRead);
        INVOCATION.set(execution);
        try {
            T result = operation.call();
            if (!execution.consumed) {
                throw OpenApiException.unavailableEndpoint();
            }
            return result;
        } finally {
            INVOCATION.remove();
        }
    }

    @RequiredArgsConstructor
    private static final class Execution {
        private final Class<?> controllerType;
        private final String sourcePath;
        private final OpenApiRequest caller;
        private final boolean moduleRead;
        private boolean consumed;
    }
}
