package me.lendev.flow.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/**
 * 审计字段的瞬态输入载体。刻意不生成 getter/toString，也不提供可序列化的原始 record， 防止原始值意外流出审计边界。
 */
@JsonIgnoreType
public final class AuditField {

    final String label;
    final Object value;
    final boolean present;
    final boolean visible;
    final String display;

    private AuditField(String label, Object value, boolean present, boolean visible, String display) {
        this.label = label;
        this.value = value;
        this.present = present;
        this.visible = visible;
        this.display = display;
    }

    /**
     * 构建存在且内容可见的字段，展示值由冻结的 JSON 值自动推导。
     *
     * @param label 字段中文标签
     * @param value 字段原始值，仅在捕获时冻结为 JSON，不会向外序列化
     * @return 可见字段载体
     */
    public static AuditField visible(String label, Object value) {
        return new AuditField(label, value, true, true, null);
    }

    /**
     * 构建存在且内容可见的字段，并指定自定义展示值。
     *
     * @param label   字段中文标签
     * @param value   字段原始值，用于变更判定
     * @param display 面向审计展示的文本，覆盖自动推导值
     * @return 可见字段载体
     */
    public static AuditField visible(String label, Object value, String display) {
        return new AuditField(label, value, true, true, display);
    }

    /**
     * 构建存在但内容脱敏的字段：参与变更判定，但审计中不展示原始值。
     *
     * @param label 字段中文标签
     * @param value 字段原始值，仅用于变更判定
     * @return 脱敏字段载体
     */
    public static AuditField hidden(String label, Object value) {
        return new AuditField(label, value, true, false, null);
    }

    /**
     * 构建不存在（缺失）的字段，用于表达对象一侧没有该字段。
     *
     * @param label   字段中文标签
     * @param visible 该侧是否允许展示此字段的存在性
     * @return 缺失字段载体
     */
    public static AuditField missing(String label, boolean visible) {
        return new AuditField(label, null, false, visible, null);
    }
}
