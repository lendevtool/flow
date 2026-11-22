package me.lendev.flow.docgen;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 保留浮点输入精度并拒绝重复键，适用于员工和开放应用提交的文档数据。
 */
public final class DocGenerationJson extends ValueDeserializer<JsonNode> {
    private static final ObjectReader READER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            // 此处只读取请求对象的一个字段，外层转换器负责检查尾随内容。
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build().reader();

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
        return READER.readTree(parser);
    }
}
