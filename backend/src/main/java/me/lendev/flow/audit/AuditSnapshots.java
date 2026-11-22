package me.lendev.flow.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

import java.util.*;

import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditChange.Cell;
import me.lendev.flow.audit.model.AuditChange.FieldChange;
import me.lendev.flow.audit.model.AuditObject;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 审计快照工具：将瞬态 {@code AuditField} 冻结为 JSON 快照， 并在前后快照之间计算安全的字段级变更明细。
 */
@Component
public class AuditSnapshots {

    private final ObjectMapper json;

    public AuditSnapshots(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 冻结后的对象字段快照：不可变且不参与序列化。
     */
    @JsonIgnoreType
    public static final class Capture {

        private final AuditObject object;
        private final Map<String, FrozenField> fields;

        private Capture(AuditObject object, Map<String, FrozenField> fields) {
            this.object = object;
            this.fields = Collections.unmodifiableMap(fields);
        }
    }

    /**
     * 单个字段的冻结值及其展示策略。
     */
    private static final class FrozenField {

        final String label;
        final JsonNode value;
        final boolean present;
        final boolean visible;
        final String display;

        FrozenField(String label, JsonNode value, boolean present, boolean visible, String display) {
            this.label = Objects.requireNonNull(label);
            this.value = value.deepCopy();
            this.present = present;
            this.visible = visible;
            this.display = display;
        }
    }

    /**
     * 将瞬态字段载体冻结为不可变 JSON 快照，原始值不会跨越审计边界留存。
     *
     * @param object 审计对象的历史标识快照
     * @param fields 字段路径到瞬态载体的映射，路径长度须在 1 至 256 字之间
     * @return 冻结后的对象快照
     * @throws IllegalArgumentException 字段路径非法时抛出
     */
    public Capture capture(AuditObject object, Map<String, AuditField> fields) {
        Map<String, FrozenField> frozen = new LinkedHashMap<>();
        fields.forEach((path, field) -> {
            if (path == null || path.isBlank() || path.length() > 256) {
                throw new IllegalArgumentException("审计字段路径非法");
            }
            frozen.put(path, new FrozenField(field.label, json.valueToTree(field.value),
                    field.present, field.visible, field.display));
        });
        return new Capture(object, frozen);
    }

    /**
     * 生成对象新建的变更明细：前快照为空，全部字段记为 ADD。
     *
     * @param after 新建后的对象快照
     * @return 新建变更明细
     */
    public AuditChange created(Capture after) {
        return compare(null, after);
    }

    /**
     * 生成对象删除的变更明细：后快照为空，全部字段记为 REMOVE。
     *
     * @param before 删除前的对象快照
     * @return 删除变更明细
     */
    public AuditChange deleted(Capture before) {
        return compare(before, null);
    }

    /**
     * 生成对象更新的变更明细：逐字段对比前后快照，按原始值判定 REPLACE/ADD/REMOVE。
     *
     * @param before 更新前的对象快照
     * @param after  更新后的对象快照，须与 before 指向同一对象标识
     * @return 更新变更明细
     * @throws IllegalArgumentException 前后对象标识不一致时抛出
     */
    public AuditChange updated(Capture before, Capture after) {
        if (!before.object.sameIdentity(after.object)) {
            throw new IllegalArgumentException("审计前后对象不一致");
        }
        return compare(before, after);
    }

    /**
     * 生成仅引用对象标识的明细（REFERENCE），不含任何字段快照，用于意图/失败事件的目标引用。
     *
     * @param object 审计对象的历史标识快照
     * @return 引用变更明细
     */
    public AuditChange reference(AuditObject object) {
        return new AuditChange(object, "REFERENCE", 1, "NONE", null, null, List.of());
    }

    private AuditChange compare(Capture before, Capture after) {
        Map<String, FrozenField> oldFields = before == null ? Map.of() : before.fields;
        Map<String, FrozenField> newFields = after == null ? Map.of() : after.fields;
        Set<String> paths = new TreeSet<>(oldFields.keySet());
        paths.addAll(newFields.keySet());
        ObjectNode oldData = before == null ? null : json.createObjectNode();
        ObjectNode newData = after == null ? null : json.createObjectNode();
        List<FieldChange> changes = new ArrayList<>();
        for (String path : paths) {
            FrozenField old = oldFields.get(path);
            FrozenField next = newFields.get(path);
            // 已存在一侧缺失该字段的可见性规则时，默认按 CHANGE_ONLY（仅记录变更）处理。
            boolean visible = visibleOnSide(before, old) && visibleOnSide(after, next);
            Cell oldCell = cell(old, visible);
            Cell newCell = cell(next, visible);
            if (oldData != null) {
                oldData.set(path, json.valueToTree(oldCell));
            }
            if (newData != null) {
                newData.set(path, json.valueToTree(newCell));
            }
            FieldChange change = fieldChange(path, old, next, oldCell, newCell);
            if (change != null) {
                changes.add(change);
            }
        }
        return new AuditChange(after == null ? before.object : after.object,
                "CHANGE", 1, "FULL_BUSINESS_FIELDS", oldData, newData, changes);
    }

    private static boolean visibleOnSide(Capture capture, FrozenField field) {
        return capture == null || field != null && field.visible;
    }

    private static FieldChange fieldChange(String path, FrozenField old, FrozenField next, Cell oldCell, Cell newCell) {
        // 使用原始值判定是否发生变化，脱敏后的展示值可能相同。
        if (oldCell.present() && newCell.present() && !Objects.equals(old.value, next.value)) {
            return new FieldChange(path, next.label, "REPLACE", oldCell, newCell);
        }
        if (oldCell.present() && !newCell.present()) {
            String label = next == null ? old.label : next.label;
            return new FieldChange(path, label, "REMOVE", oldCell, newCell);
        }
        if (!oldCell.present() && newCell.present()) {
            return new FieldChange(path, next.label, "ADD", oldCell, newCell);
        }
        return null;
    }

    private Cell cell(FrozenField field, boolean visible) {
        if (field == null || !field.present) {
            return new Cell(false, null, false, "不存在");
        }
        if (!visible) {
            return new Cell(true, null, true, "内容不展示");
        }
        String display = field.display == null ? displayValue(field.value) : field.display;
        return new Cell(true, field.value, false, display);
    }

    private static String displayValue(JsonNode value) {
        if (value.isNull()) {
            return "未设置";
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "是" : "否";
        }
        return value.isString() ? value.asString() : value.toString();
    }
}
