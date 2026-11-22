package me.lendev.flow.openapi;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import me.lendev.flow.common.response.Page;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 根据源方法声明的 DTO 生成完整响应契约，检查序列化后的结构是否仍然相符。
 */
final class EndpointGrantResponse {
    private static final String OBJECT_TYPE = "object";
    private static final String STRING_TYPE = "string";
    private static final String INTEGER_TYPE = "integer";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String FORMAT = "format";

    private EndpointGrantResponse() {
    }

    static ObjectNode describe(ObjectMapper json, ResolvableType type) {
        return describe(json, json.constructType(type.getType()), new HashSet<>());
    }

    private static ObjectNode describe(ObjectMapper json, JavaType type, Set<Class<?>> enclosing) {
        if (type == null) {
            return null;
        }
        Class<?> raw = type.getRawClass();
        if (List.class.equals(raw) || Page.class.equals(raw)) {
            return describeCollection(json, type, enclosing, Page.class.equals(raw));
        }
        if (JsonNode.class.equals(raw)) {
            // DTO 中显式声明的 JSON 值保留原业务类型，例如字段定义的默认值。
            return enclosing.isEmpty() ? null : json.createObjectNode();
        }
        if (raw.isRecord()) {
            return describeRecord(json, type, raw, enclosing);
        }
        return describeScalar(json, raw);
    }

    private static ObjectNode describeCollection(ObjectMapper json, JavaType type, Set<Class<?>> enclosing,
                                                 boolean page) {
        var item = describe(json, type.containedType(0), enclosing);
        if (item == null) {
            return null;
        }
        var array = json.createObjectNode().put("type", "array");
        array.set("items", item);
        if (!page) {
            return array;
        }
        var schema = json.createObjectNode().put("type", OBJECT_TYPE).put("additionalProperties", false);
        var properties = schema.putObject(PROPERTIES);
        properties.set("pageData", array);
        properties.putObject("total").put("type", INTEGER_TYPE).put(FORMAT, "int64");
        schema.putArray(REQUIRED).add("pageData").add("total");
        return schema;
    }

    private static ObjectNode describeRecord(ObjectMapper json, JavaType type, Class<?> raw, Set<Class<?>> enclosing) {
        if (!enclosing.add(raw)) {
            return null;
        }
        try {
            var config = json.serializationConfig();
            var introspector = config.classIntrospectorInstance().forOperation(config);
            var model = introspector.introspectForSerialization(type, introspector.introspectClassAnnotations(type));
            if (model.findAnyGetter() != null || model.findJsonValueAccessor() != null
                    || model.getClassInfo().hasAnnotation(JsonSerialize.class)) {
                return null;
            }
            var defaults = config.getDefaultPropertyInclusion().withOverrides(model.findPropertyInclusion(JsonInclude.Value.empty()))
                    .withOverrides(config.getDefaultPropertyInclusion(raw, JsonInclude.Value.empty()));
            var schema = json.createObjectNode().put("type", OBJECT_TYPE).put("additionalProperties", false);
            var fields = schema.putObject(PROPERTIES);
            var required = json.createArrayNode();
            for (var property : model.findProperties()) {
                var accessor = property.getAccessor();
                if (accessor == null) {
                    continue;
                }
                var propertyType = property.getPrimaryType();
                var field = describe(json, propertyType, enclosing);
                if (field == null || accessor.hasAnnotation(JsonUnwrapped.class) || accessor.hasAnnotation(JsonSerialize.class)) {
                    return null;
                }
                describeNullability(propertyType, field);
                var inclusion = config.getDefaultInclusion(propertyType.getRawClass(), accessor.getRawType(), defaults)
                        .withOverrides(property.findInclusion()).getValueInclusion();
                if (inclusion == JsonInclude.Include.ALWAYS || inclusion == JsonInclude.Include.USE_DEFAULTS) {
                    required.add(property.getName());
                }
                fields.set(property.getName(), field);
            }
            if (!required.isEmpty()) {
                schema.set(REQUIRED, required);
            }
            return schema;
        } finally {
            enclosing.remove(raw);
        }
    }

    private static void describeNullability(JavaType type, ObjectNode field) {
        if (!type.isPrimitive() && field.path("type").isString()) {
            String fieldType = field.path("type").asString();
            field.putArray("type").add(fieldType).add("null");
            if (field.get("enum") instanceof ArrayNode values) {
                values.addNull();
            }
        }
    }

    private static ObjectNode describeScalar(ObjectMapper json, Class<?> raw) {
        var scalar = ManagedReadRegistry.scalar(raw);
        if (scalar != null) {
            var schema = json.createObjectNode().put("type", scalar.type());
            if (scalar.format() != null) {
                schema.put(FORMAT, scalar.format());
            }
            if (raw.isEnum()) {
                var values = schema.putArray("enum");
                for (Object value : raw.getEnumConstants()) {
                    values.add(((Enum<?>) value).name());
                }
            }
            return schema;
        }
        if (raw == LocalDateTime.class) {
            return json.createObjectNode().put("type", STRING_TYPE);
        }
        if (raw == Instant.class || raw == OffsetDateTime.class) {
            return json.createObjectNode().put("type", STRING_TYPE).put(FORMAT, "date-time");
        }
        if (raw == BigInteger.class) {
            return json.createObjectNode().put("type", INTEGER_TYPE);
        }
        if (raw == BigDecimal.class || raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            return json.createObjectNode().put("type", "number");
        }
        return null;
    }

    static boolean matches(JsonNode schema, JsonNode value) {
        var type = schema.path("type");
        if (type.isMissingNode()) {
            return true;
        }
        if (type.isArray() && value.isNull()) {
            return true;
        }
        String name = type.isArray() ? type.get(0).asString() : type.asString();
        return switch (name) {
            case OBJECT_TYPE -> value.isObject() && matchingFields(schema, value);
            case "array" -> value.isArray() && matchingItems(schema.path("items"), value);
            case STRING_TYPE -> value.isString();
            case INTEGER_TYPE -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
    }

    private static boolean matchingFields(JsonNode schema, JsonNode value) {
        for (var field : schema.path(REQUIRED)) {
            if (!value.has(field.asString())) {
                return false;
            }
        }
        var properties = schema.path(PROPERTIES);
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            var expected = properties.get(field.getKey());
            if (expected == null || !matches(expected, field.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchingItems(JsonNode item, JsonNode value) {
        for (var element : value) {
            if (!matches(item, element)) {
                return false;
            }
        }
        return true;
    }
}
