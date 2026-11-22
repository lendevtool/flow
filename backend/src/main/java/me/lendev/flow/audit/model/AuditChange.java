package me.lendev.flow.audit.model;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 单个对象的审计变更明细：仅包含安全数据，原始捕获绝不跨越记录器边界。
 */
public record AuditChange(AuditObject object, String entryType, int snapshotVersion,
                          String snapshotScope, JsonNode beforeData, JsonNode afterData,
                          List<FieldChange> changes) {

    public AuditChange {
        // Jackson 3 会把 JSON null 物化为 NullNode。
        // 在整快照边界上，null 表示缺失的对象侧或 SQL NULL。
        beforeData = beforeData == null || beforeData.isNull() ? null : beforeData.deepCopy();
        afterData = afterData == null || afterData.isNull() ? null : afterData.deepCopy();
        changes = List.copyOf(changes);
    }

    /**
     * @return 是否为发生实际变化的 CHANGE 明细：新建/删除（前后快照一侧缺失）或存在字段级变更
     */
    public boolean changed() {
        return "CHANGE".equals(entryType)
                && ((beforeData == null) != (afterData == null) || !changes.isEmpty());
    }

    /**
     * 字段级变更记录：路径、标签、变更类型及前后单元格。
     */
    public record FieldChange(String path, String label, String changeType, Cell before, Cell after) {

    }

    /**
     * 字段单元格：{@code present} 标记字段是否存在，{@code redacted} 标记内容已脱敏， {@code value} 为冻结的 JSON 值。
     */
    public record Cell(boolean present, JsonNode value, boolean redacted, String display) {

        public Cell {
            value = value == null ? null : value.deepCopy();
        }
    }
}
