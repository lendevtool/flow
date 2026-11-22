package me.lendev.flow.openapi;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;

/**
 * 发现通用读取入口可安全绑定的标量查询参数及具名来源路径变量。
 */
final class ManagedReadInputs {
    private static final String VARIABLE = "[a-zA-Z]\\w*";
    private static final Pattern SOURCE_SEGMENT = Pattern.compile("(?:[\\w-]+|\\{" + VARIABLE + "})");
    private static final Pattern PATH_VARIABLE = Pattern.compile("/\\{(" + VARIABLE + ")}");
    private static final Pattern PARAMETER_NAME = Pattern.compile(VARIABLE);

    private ManagedReadInputs() {
    }

    static Description describe(String path, HandlerMethod handler) {
        if (!validSourcePath(path)) {
            return null;
        }
        var variableNames = PATH_VARIABLE.matcher(path).results().map(result -> result.group(1)).toList();
        Set<String> unbound = new HashSet<>(variableNames);
        if (unbound.size() != variableNames.size()) {
            return null;
        }
        var inputs = new ArrayList<ManagedReadRegistry.Parameter>();
        var signature = new ArrayList<String>();
        for (var parameter : handler.getMethod().getParameters()) {
            if (!describeArgument(parameter, inputs, unbound, signature)) {
                return null;
            }
        }
        if (!unbound.isEmpty() || inputs.stream().map(ManagedReadRegistry.Parameter::name).distinct().count() != inputs.size()) {
            return null;
        }
        return new Description(inputs.stream().sorted(Comparator.comparing(ManagedReadRegistry.Parameter::name)).toList(),
                variableNames, signature);
    }

    private static boolean validSourcePath(String path) {
        if (!path.startsWith("/")) {
            return false;
        }
        // 逐段校验避免长路径在重复的正则分组中耗尽调用栈，保留空段和尾斜杠的拒绝语义。
        for (String segment : path.substring(1).split("/", -1)) {
            if (!SOURCE_SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean describeArgument(Parameter parameter, List<ManagedReadRegistry.Parameter> inputs,
                                            Set<String> unbound, List<String> signature) {
        var variable = parameter.getAnnotation(PathVariable.class);
        var query = parameter.getAnnotation(RequestParam.class);
        if (variable == null && query == null) {
            return describeQueryRecord(parameter, inputs, signature);
        }
        if (variable != null && query != null || Arrays.stream(parameter.getAnnotations()).anyMatch(annotation ->
                annotation.annotationType() != PathVariable.class && annotation.annotationType() != RequestParam.class
                        && annotation.annotationType() != DateTimeFormat.class
                        && !annotation.annotationType().isAnnotationPresent(Constraint.class))) {
            return false;
        }
        signature.addAll(annotationNames(parameter));
        if (variable != null) {
            String name = name(parameter, variable.name(), variable.value());
            return variable.required() && unbound.remove(name)
                    && add(inputs, name, parameter.getType(), parameter.getAnnotation(DateTimeFormat.class), true);
        }
        return ValueConstants.DEFAULT_NONE.equals(query.defaultValue())
                && add(inputs, name(parameter, query.name(), query.value()), parameter.getType(),
                parameter.getAnnotation(DateTimeFormat.class), query.required());
    }

    private static boolean describeQueryRecord(Parameter parameter, List<ManagedReadRegistry.Parameter> inputs,
                                               List<String> signature) {
        // 扁平记录交给 MVC 的构造器绑定与 Bean Validation，契约禁止嵌套属性及多值。
        Class<?> type = parameter.getType();
        if (!type.isRecord() || parameter.getAnnotations().length != 1 || parameter.getAnnotation(Valid.class) == null) {
            return false;
        }
        var components = type.getRecordComponents();
        var constructorTypes = Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            var constructor = type.getDeclaredConstructor(constructorTypes);
            var arguments = constructor.getParameters();
            var names = BeanUtils.getParameterNames(constructor);
            for (int index = 0; index < components.length; index++) {
                var component = components[index];
                var argument = arguments[index];
                var binding = MethodParameter.forFieldAwareConstructor(constructor, index, names[index]);
                var format = argument.getAnnotation(DateTimeFormat.class);
                boolean required = component.getType().isPrimitive() || argument.isAnnotationPresent(NotNull.class);
                if (!component.getName().equals(names[index]) || binding.hasParameterAnnotation(BindParam.class)
                        || !add(inputs, component.getName(), component.getType(), format, required)) {
                    return false;
                }
                signature.add(component.getName() + ":" + annotationNames(argument));
            }
            return true;
        } catch (NoSuchMethodException missingCanonicalConstructor) {
            return false;
        }
    }

    private static List<String> annotationNames(Parameter parameter) {
        return Arrays.stream(parameter.getAnnotations()).map(Object::toString).sorted().toList();
    }

    private static String name(Parameter parameter, String name, String value) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() && parameter.isNamePresent() ? parameter.getName() : value;
    }

    private static boolean add(List<ManagedReadRegistry.Parameter> inputs, String name, Class<?> type,
                               DateTimeFormat format, boolean required) {
        var scalar = ManagedReadRegistry.scalar(type);
        if (!PARAMETER_NAME.matcher(name).matches() || scalar == null || !ManagedReadRegistry.validFormat(type, format)
                || !required && type.isPrimitive()) {
            return false;
        }
        inputs.add(new ManagedReadRegistry.Parameter(name, scalar.type(), required, scalar.format()));
        return true;
    }

    record Description(List<ManagedReadRegistry.Parameter> parameters, List<String> pathVariables,
                       List<String> constraints) {
    }
}
