package me.lendev.flow.param.support;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.param.domain.ParamItem;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 参数动态字段的校验器：在 API 边界统一校验字段配置（schema）与字段值。 前端 UI 的校验不可信，所有配置与数据必须在服务端重新校验，防止非法结构写入数据库。
 */
@Component
public class ParamFieldValidator {

    private static final String NUMBER = "number";
    private static final String BOOLEAN = "boolean";
    private static final String SELECT = "select";
    private static final String LABEL = "label";
    private static final String VALUE = "value";
    private static final Set<String> TYPES = Set.of("text", NUMBER, BOOLEAN, SELECT);
    private static final Set<String> RESERVED = Set.of("__proto__", "constructor", "prototype");

    /**
     * 校验参数组的字段配置 schema：固定字段只允许 value1～value9， 扩展字段标识需符合命名规则且不重复，各字段定义的类型、选项、默认值均须合法。
     *
     * @param config 字段配置 JSON，可为空（表示无动态字段）
     */
    public void schema(JsonNode config) {
        object(config, "error.param.schema.object");
        if (absent(config)) {
            return;
        }
        if (config.toString().length() > 65536) {
            fail("error.param.schema.size");
        }
        fixedSchema(config.get("fixedFields"));
        Set<String> keys = new HashSet<>();
        for (JsonNode field : extras(config)) {
            definition(field, false);
            String key = field.path("key").asString("");
            if (!key.matches("[A-Za-z]\\w{0,63}") || RESERVED.contains(key) || !keys.add(key)) {
                fail("error.param.schema.extra-key.invalid");
            }
        }
    }

    private void fixedSchema(JsonNode fixed) {
        object(fixed, "error.param.schema.fixed.object");
        if (absent(fixed)) {
            return;
        }
        for (var entry : fixed.properties()) {
            String key = entry.getKey();
            JsonNode field = entry.getValue();
            if (!key.matches("value[1-9]")) {
                fail("error.param.schema.fixed-key.invalid");
            }
            if (field.has("key") && !key.equals(field.path("key").asString())) {
                fail("error.param.schema.fixed-key.immutable");
            }
            definition(field, true);
        }
    }

    private void definition(JsonNode field, boolean fixed) {
        object(field, "error.param.schema.field.object");
        if (absent(field)) {
            fail("error.param.schema.field.required");
        }
        JsonNode label = field.get(LABEL);
        if (label == null || !label.isString() || label.asString().isBlank() || label.asString().length() > 100) {
            fail("error.param.schema.field.label.size");
        }
        String type = field.path("type").asString("text");
        if (!TYPES.contains(type)) {
            fail("error.param.schema.field.type.invalid");
        }
        for (String flag : List.of("required", "showInTable")) {
            if (field.has(flag) && !field.get(flag).isBoolean()) {
                fail("error.param.schema.field.flag.invalid");
            }
        }
        if (SELECT.equals(type)) {
            selectOptions(field.get("options"));
        }
        if (field.has("defaultValue")) {
            JsonNode value = field.get("defaultValue");
            validateValue(field, value, false);
            if (fixed && !absent(value) && value.asString().length() > 512) {
                fail("error.param.schema.default.size");
            }
        }
    }

    private void selectOptions(JsonNode options) {
        if (options == null || !options.isArray() || options.isEmpty() || options.size() > 100) {
            fail("error.param.schema.options.size");
        }
        Set<String> values = new HashSet<>();
        for (JsonNode option : options) {
            JsonNode label = option.path(LABEL);
            JsonNode value = option.path(VALUE);
            if (!option.isObject() || !label.isString() || label.asString().isBlank()
                    || !value.isString() || value.asString().isBlank() || value.asString().length() > 512
                    || !values.add(value.asString())) {
                fail("error.param.schema.option.invalid");
            }
        }
    }

    /**
     * 按字段配置校验参数记录的固定字段值与扩展字段值： 校验类型匹配、必填项、选项值合法性及扩展值大小上限。
     *
     * @param config 参数组的字段配置 JSON
     * @param item   待校验的参数记录
     */
    public void values(JsonNode config, ParamItem item) {
        object(item.getExtra(), "error.param.extra-values.object");
        if (!absent(item.getExtra()) && item.getExtra().toString().length() > 65536) {
            fail("error.param.extra-values.size");
        }
        if (absent(config)) {
            return;
        }
        JsonNode fixed = config.get("fixedFields");
        if (!absent(fixed)) {
            for (var entry : fixed.properties()) {
                String raw = item.value(entry.getKey().charAt(5) - '0');
                validateFixed(entry.getValue(), raw);
            }
        }
        for (JsonNode field : extras(config)) {
            JsonNode value = absent(item.getExtra()) ? null : item.getExtra().get(field.path("key").asString());
            validateValue(field, value, true);
        }
    }

    private void validateFixed(JsonNode field, String raw) {
        if (raw == null || raw.isBlank()) {
            required(field);
            return;
        }
        String type = field.path("type").asString("text");
        boolean valid = switch (type) {
            case NUMBER -> number(raw);
            case BOOLEAN -> "true".equals(raw) || "false".equals(raw);
            case SELECT -> option(field, raw);
            default -> true;
        };
        if (!valid) {
            fail("error.param.field.value.invalid", field.path(LABEL).asString());
        }
    }

    private void validateValue(JsonNode field, JsonNode value, boolean checkRequired) {
        if (absent(value) || value.isString() && value.asString().isBlank()) {
            if (checkRequired) {
                required(field);
            }
            return;
        }
        String type = field.path("type").asString("text");
        boolean valid = switch (type) {
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case SELECT -> value.isString() && option(field, value.asString());
            default -> value.isString() && value.asString().length() <= 4096;
        };
        if (!valid) {
            fail("error.param.field.value.invalid", field.path(LABEL).asString());
        }
    }

    private boolean number(String raw) {
        try {
            new BigDecimal(raw);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private boolean option(JsonNode field, String value) {
        for (JsonNode option : field.path("options")) {
            if (value.equals(option.path(VALUE).asString())) {
                return true;
            }
        }
        return false;
    }

    private void required(JsonNode field) {
        if (field.path("required").asBoolean(false)) {
            fail("error.param.field.value.required", field.path(LABEL).asString());
        }
    }

    /**
     * 校验字段配置变更：删除某个已配置且仍有持久化数据的扩展字段时， 必须先显式迁移或清空数据，否则会产生无法读取的孤儿数据。
     *
     * @param oldConfig 变更前的字段配置
     * @param next      变更后的字段配置
     * @param items     该参数组下已有的参数记录，用于检查存量数据
     */
    public void change(JsonNode oldConfig, JsonNode next, List<ParamItem> items) {
        Set<String> nextKeys = StreamSupport.stream(extras(next).spliterator(), false)
                .map(field -> field.path("key").asString()).collect(Collectors.toSet());
        for (JsonNode field : extras(oldConfig)) {
            String key = field.path("key").asString();
            if (!nextKeys.contains(key) && items.stream().anyMatch(item ->
                    !absent(item.getExtra()) && !absent(item.getExtra().get(key)))) {
                fail("error.param.field.has-data", field.path(LABEL).asString());
            }
        }
        for (ParamItem item : items) {
            values(next, item);
        }
    }

    private Iterable<JsonNode> extras(JsonNode config) {
        if (absent(config) || absent(config.get("extraFields"))) {
            return List.of();
        }
        JsonNode fields = config.get("extraFields");
        if (!fields.isArray() || fields.size() > 50) {
            fail("error.param.schema.extra-fields.invalid");
        }
        return fields;
    }

    private void object(JsonNode value, String messageKey) {
        if (!absent(value) && !value.isObject()) {
            fail(messageKey);
        }
    }

    private boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private void fail(String messageKey, Object... arguments) {
        throw BadRequestException.localized(messageKey, arguments);
    }
}
