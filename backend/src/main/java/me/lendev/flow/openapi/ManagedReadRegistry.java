package me.lendev.flow.openapi;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 从当前 MVC 映射发现现有 REST 路由，并独立校验只读接口的开放资格及公开契约。
 */
@Component
public class ManagedReadRegistry implements SmartInitializingSingleton {
    public static final String ROOT = "/open/v1/managed";
    public static final String PATH_PREFIX = ROOT + '/';
    private static final String STRING_TYPE = "string";
    private static final Pattern PUBLIC_PATH = Pattern.compile(Pattern.quote(PATH_PREFIX) + "[a-z0-9]++(?:-[a-z0-9]++)*+");
    private static final Pattern SOURCE_PATH = Pattern.compile("(?:/[a-zA-Z0-9_-]++)++");
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ApplicationContext context;
    private final List<ManagedReadModule> modules;
    private final ObjectMapper json;
    private Map<String, Source> sources = Map.of();
    private List<Route> routes = List.of();

    public ManagedReadRegistry(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings,
                               ApplicationContext context, List<ManagedReadModule> modules, ObjectMapper json) {
        this.mappings = mappings;
        this.context = context;
        this.modules = modules;
        this.json = json;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var entries = new HashMap<String, Source>();
        var ambiguous = new HashSet<String>();
        var handlers = mappings.getObject().getHandlerMethods();
        var customAdvice = ControllerAdviceBean.findAnnotatedBeans(context).stream()
                .filter(advice -> advice.getBeanType() != null && hasCustomBinding(advice.getBeanType())).toList();
        var getPaths = new HashMap<String, Integer>();
        var mixedControllers = new HashSet<Class<?>>();
        handlers.forEach((info, handler) -> {
            var methods = info.getMethodsCondition().getMethods();
            if (!methods.equals(Set.of(RequestMethod.GET))) {
                mixedControllers.add(handler.getBeanType());
            }
            if (methods.isEmpty() || methods.contains(RequestMethod.GET)) {
                info.getPatternValues().forEach(path -> getPaths.merge(path, 1, Integer::sum));
            }
        });
        var readers = new ArrayList<>(modules);
        handlers.values().stream().map(HandlerMethod::getBeanType).distinct().forEach(type -> {
            var annotation = type.getAnnotation(EndpointGrantRead.class);
            if (annotation != null) {
                readers.add(new InterfaceGrantModule(type, annotation.value()));
            }
        });
        for (var module : readers) {
            if (!eligibleModule(module, mixedControllers, customAdvice)) {
                continue;
            }
            handlers.forEach((info, handler) -> {
                var source = discover(module, info, handler);
                if (source != null && getPaths.getOrDefault(source.path(), 0) == 1
                        && entries.putIfAbsent(source.path(), source) != null) {
                    ambiguous.add(source.path());
                }
            });
        }
        ambiguous.forEach(entries::remove);
        sources = Map.copyOf(entries);
        routes = discoverRoutes(handlers);
    }

    private static boolean eligibleModule(ManagedReadModule module, Set<Class<?>> mixedControllers,
                                          List<ControllerAdviceBean> customAdvice) {
        Class<?> type = module.controllerType();
        return (module instanceof ManagedReadPolicy || !mixedControllers.contains(type))
                && !hasCustomBinding(type) && !AnnotatedElementUtils.hasAnnotation(type, Validated.class)
                && customAdvice.stream().noneMatch(advice -> advice.isApplicableToBeanType(type));
    }

    private static List<Route> discoverRoutes(Map<RequestMappingInfo, HandlerMethod> handlers) {
        var result = new HashSet<Route>();
        handlers.forEach((info, handler) -> {
            var type = handler.getBeanType();
            if (!type.getPackageName().startsWith("me.lendev.flow.")
                    || !AnnotatedElementUtils.hasAnnotation(type, RestController.class)) {
                return;
            }
            var methods = info.getMethodsCondition().getMethods();
            for (var path : info.getPatternValues()) {
                if (path.startsWith("/open/") || path.startsWith("/oauth2/") || path.equals("/admin/open-api")
                        || path.startsWith("/admin/open-api/")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    result.add(new Route(path, "ANY"));
                } else {
                    methods.forEach(method -> result.add(new Route(path, method.name())));
                }
            }
        });
        return result.stream().sorted(Comparator.comparing(Route::path).thenComparing(Route::method)).toList();
    }

    private static boolean hasCustomBinding(Class<?> type) {
        return !MethodIntrospector.selectMethods(type, (MethodFilter) method ->
                AnnotatedElementUtils.hasAnnotation(method, InitBinder.class)
                        || AnnotatedElementUtils.hasAnnotation(method, ModelAttribute.class)).isEmpty();
    }

    /**
     * 返回已部署的内部 REST 路由，包含当前只读阶段尚不可导入的路由。
     */
    public List<Route> routes() {
        return routes;
    }

    public Source source(String path) {
        return path == null ? null : sources.get(path);
    }

    public static boolean isPublicPath(String path) {
        return path != null && path.length() <= 256 && PUBLIC_PATH.matcher(path).matches();
    }

    /**
     * 读取已保存的公开快照。遇到旧格式或无效配置时停止使用该映射。
     */
    public Contract readContract(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, Contract.class);
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    public String writeContract(Contract contract) {
        return json.writeValueAsString(contract);
    }

    /**
     * 确认来源、公开契约以及目录中的数据范围与部署一致。
     */
    public boolean matches(String path, String method, String version, String dataScopeType, String sourcePath,
                           String hash, String storedContract) {
        return "GET".equals(method) && "v1".equals(version) && isPublicPath(path)
                && matchingBinding(sourcePath, hash, storedContract, dataScopeType) != null;
    }

    Binding matchingBinding(String sourcePath, String hash, String storedContract, String dataScopeType) {
        var source = source(sourcePath);
        if (source == null || !source.hash().equals(hash) || !source.dataScopeType().equals(dataScopeType)) {
            return null;
        }
        var contract = readContract(storedContract);
        return contract != null && contract.equals(source.contract(contract.fieldNames())) ? new Binding(source, contract) : null;
    }

    private Source discover(ManagedReadModule module, RequestMappingInfo info, HandlerMethod handler) {
        if (!handler.getBeanType().equals(module.controllerType()) || !simpleGet(info) || info.getPatternValues().size() != 1
                || !AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RestController.class)) {
            return null;
        }
        String path = info.getPatternValues().iterator().next();
        if (path.startsWith("/open/") || path.startsWith("/admin/") || path.startsWith("/auth/")
                || path.startsWith("/oauth2/") || path.startsWith("/system-policy/")) {
            return null;
        }
        var validate = handler.createWithValidateFlags();
        boolean endpointOnly = module instanceof InterfaceGrantModule;
        if (validate.shouldValidateReturnValue() || !endpointOnly && validate.shouldValidateArguments()) {
            return null;
        }
        var inputs = module instanceof ManagedReadPolicy policy ? legacyInputs(policy, path, handler)
                : ManagedReadInputs.describe(path, handler);
        return describeResponse(module, handler, path, inputs);
    }

    private Source describeResponse(ManagedReadModule module, HandlerMethod handler, String path,
                                    ManagedReadInputs.Description inputs) {
        var result = ResolvableType.forMethodReturnType(handler.getMethod(), handler.getBeanType());
        if (inputs == null || !R.class.equals(result.resolve())) {
            return null;
        }
        boolean legacy = module instanceof ManagedReadPolicy;
        boolean endpointOnly = module instanceof InterfaceGrantModule;
        var data = result.getGeneric(0);
        boolean collection = List.class.equals(data.resolve());
        boolean page = !legacy && Page.class.equals(data.resolve());
        Class<?> responseType = (collection || page ? data.getGeneric(0) : data).resolve();
        var schema = endpointOnly ? EndpointGrantResponse.describe(json, data) : null;
        var fields = endpointOnly ? List.<Field>of() : fields(module.responseFields(), responseType);
        if (endpointOnly ? schema == null : fields.isEmpty()) {
            return null;
        }
        var full = new Contract(inputs.parameters(), fields, collection, module.dataScopeType(), module.scopePolicyVersion(),
                page, inputs.pathVariables(), inputs.constraints(), schema);
        var signature = new SourceSignature(path, handler.getMethod().toGenericString(), module.moduleCode(),
                legacy ? full.legacyContract() : full);
        return new Source(path, module.moduleCode(), full, handler, responseType, sha256(json.writeValueAsString(signature)), !legacy);
    }

    private static ManagedReadInputs.Description legacyInputs(ManagedReadPolicy policy, String path, HandlerMethod handler) {
        if (!policy.sourcePaths().contains(path) || !SOURCE_PATH.matcher(path).matches()) {
            return null;
        }
        return parameters(handler).map(values -> new ManagedReadInputs.Description(values, List.of(), List.of())).orElse(null);
    }

    private static boolean simpleGet(RequestMappingInfo info) {
        var produces = info.getProducesCondition();
        return info.getMethodsCondition().getMethods().equals(Set.of(RequestMethod.GET))
                && info.getParamsCondition().isEmpty() && info.getHeadersCondition().isEmpty()
                && info.getConsumesCondition().isEmpty() && info.getVersionCondition().isEmpty()
                && info.getCustomCondition() == null && (produces.isEmpty()
                || produces.getExpressions().size() == 1 && produces.getExpressions().stream()
                .allMatch(value -> !value.isNegated() && MediaType.APPLICATION_JSON.equals(value.getMediaType())));
    }

    private static Optional<List<Parameter>> parameters(HandlerMethod handler) {
        List<Parameter> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var parameter : handler.getMethod().getParameters()) {
            var requestParam = parameter.getAnnotation(RequestParam.class);
            if (requestParam == null || !ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue())
                    || !supportedAnnotations(parameter.getAnnotations())
                    || !requestParam.required() && parameter.getType().isPrimitive()) {
                return Optional.empty();
            }
            String name = requestParam.name().isEmpty() ? requestParam.value() : requestParam.name();
            if (name.isEmpty() && parameter.isNamePresent()) {
                name = parameter.getName();
            }
            var scalar = scalar(parameter.getType());
            var format = parameter.getAnnotation(DateTimeFormat.class);
            if (name.isEmpty() || !names.add(name) || scalar == null || !validFormat(parameter.getType(), format)) {
                return Optional.empty();
            }
            result.add(new Parameter(name, scalar.type(), requestParam.required(), scalar.format()));
        }
        return Optional.of(result.stream().sorted(Comparator.comparing(Parameter::name)).toList());
    }

    static boolean validFormat(Class<?> type, DateTimeFormat format) {
        if (type != LocalDate.class) {
            return format == null;
        }
        return format != null && format.iso() == DateTimeFormat.ISO.DATE && format.pattern().isEmpty()
                && format.fallbackPatterns().length == 0;
    }

    private static boolean supportedAnnotations(Annotation[] annotations) {
        for (var annotation : annotations) {
            if (annotation.annotationType() != RequestParam.class && annotation.annotationType() != DateTimeFormat.class) {
                return false;
            }
        }
        return true;
    }

    private static List<Field> fields(Map<Class<?>, Set<String>> allowed, Class<?> responseType) {
        if (responseType == null || !responseType.isRecord() || !allowed.containsKey(responseType)) {
            return List.of();
        }
        List<Field> result = new ArrayList<>();
        for (RecordComponent field : responseType.getRecordComponents()) {
            if (allowed.get(responseType).contains(field.getName())) {
                var scalar = scalar(field.getType());
                if (scalar == null) {
                    return List.of();
                }
                result.add(new Field(field.getName(), scalar.type(), !field.getType().isPrimitive(), scalar.format()));
            }
        }
        return result.size() == allowed.get(responseType).size()
                ? result.stream().sorted(Comparator.comparing(Field::name)).toList() : List.of();
    }

    static Scalar scalar(Class<?> type) {
        if (type == String.class) {
            return new Scalar(STRING_TYPE, null);
        }
        if (type == LocalDate.class) {
            return new Scalar(STRING_TYPE, "date");
        }
        if (type == int.class || type == Integer.class) {
            return new Scalar("integer", "int32");
        }
        if (type == long.class || type == Long.class) {
            return new Scalar("integer", "int64");
        }
        if (type == boolean.class || type == Boolean.class) {
            return new Scalar("boolean", null);
        }
        if (type.isEnum()) {
            return new Scalar(STRING_TYPE, null);
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Parameter(String name, String type, boolean required, String format) {
    }

    public record Field(String name, String type, boolean nullable, String format) {
    }

    public record Route(String path, String method) {
    }

    public record Contract(List<Parameter> parameters, List<Field> fields, boolean collection, String dataScopeType,
                           String scopePolicyVersion,
                           @JsonProperty("page") @JsonInclude(JsonInclude.Include.NON_NULL) Boolean pageMarker,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> pathVariables,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> parameterConstraints,
                           @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode responseSchema) {
        public Contract {
            parameters = List.copyOf(parameters);
            fields = List.copyOf(fields);
            pageMarker = Boolean.TRUE.equals(pageMarker) ? Boolean.TRUE : null;
            pathVariables = pathVariables == null ? List.of() : List.copyOf(pathVariables);
            parameterConstraints = parameterConstraints == null ? List.of() : List.copyOf(parameterConstraints);
        }

        public boolean wholeResponse() {
            return responseSchema != null;
        }

        public Set<String> fieldNames() {
            return fields.stream().map(Field::name).collect(Collectors.toUnmodifiableSet());
        }

        public boolean page() {
            return Boolean.TRUE.equals(pageMarker);
        }

        private LegacyContract legacyContract() {
            return new LegacyContract(parameters, fields, collection, dataScopeType, scopePolicyVersion);
        }
    }

    public record Source(String path, String moduleCode, Contract fullContract, HandlerMethod handler,
                         Class<?> responseType, String hash, boolean moduleRead) {
        public String dataScopeType() {
            return fullContract.dataScopeType();
        }

        public Contract contract(Set<String> names) {
            if (fullContract.wholeResponse()) {
                return names.isEmpty() ? fullContract : null;
            }
            var fields = fullContract.fields().stream().filter(field -> names.contains(field.name())).toList();
            if (fields.isEmpty() || fields.size() != names.size()) {
                return null;
            }
            return new Contract(fullContract.parameters(), fields, fullContract.collection(), dataScopeType(),
                    fullContract.scopePolicyVersion(), fullContract.page(), fullContract.pathVariables(),
                    fullContract.parameterConstraints(), null);
        }
    }

    record Binding(Source source, Contract contract) {
    }

    record Scalar(String type, String format) {
    }

    private record LegacyContract(List<Parameter> parameters, List<Field> fields, boolean collection,
                                  String dataScopeType,
                                  String scopePolicyVersion) {
    }

    private record InterfaceGrantModule(Class<?> controllerType, String moduleCode) implements ManagedReadModule {
        @Override
        public String dataScopeType() {
            return "NONE";
        }

        @Override
        public String scopePolicyVersion() {
            return "endpoint-grant-v1";
        }

        @Override
        public Map<Class<?>, Set<String>> responseFields() {
            return Map.of();
        }
    }

    private record SourceSignature(String path, String handler, String moduleCode, Object contract) {
    }
}
