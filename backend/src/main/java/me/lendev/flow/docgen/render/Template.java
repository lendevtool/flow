package me.lendev.flow.docgen.render;

import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一个固定版本的渲染输入，保存传入内容的独立副本。
 *
 * @param file   已锁定版本的 DOCX 字节
 * @param schema 2020-12 输入对象 Schema
 * @param config 标签与本地渲染策略的配置，可省略
 */
public record Template(byte[] file, JsonNode schema, JsonNode config) {
    public Template {
        file = file.clone();
        schema = schema.deepCopy();
        config = config == null || config.isNull() ? InputSchema.JSON.createObjectNode() : config.deepCopy();
    }

    @Override
    public byte[] file() {
        return file.clone();
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    @Override
    public JsonNode config() {
        return config.deepCopy();
    }

    @Override
    public boolean equals(Object value) {
        if (value == this)
            return true;
        if (value == null || value.getClass() != Template.class)
            return false;
        // 直接比较记录内部的快照，避免比较时再次复制文件及 JSON。
        Template other = (Template) value;
        return Arrays.equals(file, other.file) && schema.equals(other.schema)
                && config.equals(other.config);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(file), schema, config);
    }

    @Override
    public String toString() {
        return "Template[fileBytes=" + file.length + "]";
    }
}
