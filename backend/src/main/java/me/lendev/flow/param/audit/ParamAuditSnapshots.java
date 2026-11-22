package me.lendev.flow.param.audit;

import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.param.domain.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 参数模块审计快照构建器：将 {@code ParamGroup}/{@code ParamItem} 转换为审计对象引用与字段快照， 其中下拉、布尔类型的值会依据分类字段配置转换为可读的展示文本。
 */
@Component
@RequiredArgsConstructor
public class ParamAuditSnapshots {

    private static final String LABEL = "label";

    private final AuditSnapshots snapshots;

    public AuditObject ref(ParamRecord row) {
        ParamItem item = row instanceof ParamItem value ? value : null;
        return new AuditObject(item == null ? "PARAM_GROUP" : "PARAM_ITEM", row.getId().toString(),
                row.getCode(), row.getName(), item == null ? null : "PARAM_GROUP",
                item == null ? null : item.getGroupId().toString());
    }

    private Map<String, AuditField> basic(ParamRecord row) {
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("code", AuditField.visible("编码", row.getCode()));
        fields.put("name", AuditField.visible("名称", row.getName()));
        fields.put("nameEn", AuditField.visible("英文名称", row.getNameEn()));
        fields.put("enable", AuditField.visible("启用", row.getEnable()));
        fields.put("sort", AuditField.visible("序号", row.getSort()));
        return fields;
    }

    public AuditSnapshots.Capture group(ParamGroup group) {
        Map<String, AuditField> fields = basic(group);
        fields.put("remark", AuditField.visible("备注", group.getRemark()));
        fields.put("fieldConfig", AuditField.visible("字段配置", group.getFieldConfig()));
        return snapshots.capture(ref(group), fields);
    }

    public AuditSnapshots.Capture item(ParamGroup group, ParamItem item) {
        Map<String, AuditField> fields = basic(item);
        fields.put("remark", AuditField.visible("备注", item.getRemark()));
        JsonNode config = group.getFieldConfig();
        for (int i = 1; i <= 9; i++) {
            String path = "value" + i;
            JsonNode definition = config == null ? null : config.path("fixedFields").path(path);
            String label = definition == null ? "值" + i : definition.path(LABEL).asString("值" + i);
            fields.put(path, field(label, item.value(i), definition));
        }
        captureExtraFields(fields, config, item.getExtra());
        return snapshots.capture(ref(item), fields);
    }

    private void captureExtraFields(Map<String, AuditField> fields, JsonNode config, JsonNode extra) {
        JsonNode unknown = extra == null ? null : extra.deepCopy();
        if (config != null) {
            for (JsonNode definition : config.path("extraFields")) {
                String key = definition.path("key").asString();
                String path = "extra." + key;
                String label = definition.path(LABEL).asString(key);
                boolean present = extra != null && extra.isObject() && extra.has(key);
                fields.put(path, present ? field(label, extra.get(key), definition)
                        : AuditField.missing(label, true));
                if (unknown instanceof ObjectNode node) {
                    node.remove(key);
                }
            }
        }
        fields.put("extra", AuditField.visible("其他扩展值", unknown));
    }

    public AuditSnapshots.Capture order(ParamRecord row) {
        return snapshots.capture(ref(row), Map.of("sort", AuditField.visible("序号", row.getSort())));
    }

    private AuditField field(String label, Object value, JsonNode definition) {
        return AuditField.visible(label, value, display(value, definition));
    }

    private String display(Object value, JsonNode definition) {
        if (value == null || definition == null) {
            return null;
        }
        String text = value instanceof JsonNode node ? node.asString() : value.toString();
        String type = definition.path("type").asString();
        if ("select".equals(type)) {
            String label = null;
            for (JsonNode option : definition.path("options")) {
                if (text.equals(option.path("value").asString())) {
                    label = option.path(LABEL).asString();
                }
            }
            return label;
        }
        if ("boolean".equals(type)) {
            if ("true".equals(text)) {
                return "是";
            }
            return "false".equals(text) ? "否" : null;
        }
        return null;
    }
}
