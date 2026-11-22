package me.lendev.flow.docgen.render;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

import static me.lendev.flow.docgen.render.RenderFailure.require;

final class InputSchema {
    static final JsonMapper JSON = new JsonMapper();
    static final String ITEMS = "items";
    private static final String SCHEMA = "$schema";
    private static final String PROPERTIES = "properties";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String FORMAT = "format";
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Set<String> KEYWORDS = Set.of(SCHEMA, "$defs", "$ref", "type", "title", "description",
            PROPERTIES, "required", ADDITIONAL_PROPERTIES, ITEMS, "enum", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties",
            FORMAT);
    private final JsonNode root;
    private final Schema compiled;
    private final Set<JsonNode> declared = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    InputSchema(JsonNode root) {
        this.root = root;
        limit(root, 128 * 1024, false);
        inspect(root);
        for (var node : declared) {
            if (node.has("$ref"))
                resolve(node);
        }
        require("object".equals(resolve(root).path("type").asString("")), "输入 Schema 根必须是对象");
        var errors = REGISTRY.getSchema(SchemaLocation.of(DIALECT)).validate(root);
        require(errors.isEmpty(), "Schema 不符合 2020-12 规范");
        compiled = REGISTRY.getSchema(root);
        compiled.initializeValidators();
    }

    private void inspect(JsonNode node) {
        declared.add(node);
        require(node.isObject() && KEYWORDS.containsAll(node.propertyNames()), "Schema 使用了未支持的关键字");
        require(!node.has(SCHEMA) || DIALECT.equals(node.get(SCHEMA).asString("")), "Schema 方言不支持");
        require(!node.has(FORMAT) || "date".equals(node.get(FORMAT).asString("")), "仅支持 date 日期格式");
        if (node.has("$ref")) {
            require(node.size() == 1, "引用节点仅可声明本地引用");
        }
        for (var map : Set.of("$defs", PROPERTIES)) node.path(map).forEach(this::inspect);
        if (node.has(ITEMS))
            inspect(node.get(ITEMS));
        if (node.has(ADDITIONAL_PROPERTIES) && !node.get(ADDITIONAL_PROPERTIES).isBoolean()) {
            inspect(node.get(ADDITIONAL_PROPERTIES));
        }
    }

    JsonNode resolve(JsonNode node) {
        var visited = new HashSet<String>();
        while (node.has("$ref")) {
            String ref = node.get("$ref").asString("");
            require(ref.length() <= 512 && ref.startsWith("#/") && visited.size() < 32 && visited.add(ref),
                    "模板只接受可解析的本地引用");
            node = root.at(ref.substring(1));
            require(declared.contains(node), "本地引用目标必须是已声明的 Schema");
        }
        return node;
    }

    JsonNode field(JsonNode schema, String path) {
        var current = schema;
        for (var part : path.split("\\.")) current = resolve(current).path(PROPERTIES).path(part);
        return resolve(current);
    }

    void validate(JsonNode data) {
        limit(data, 4 * 1024 * 1024, true);
        var errors = compiled.validate(data, context -> context.executionConfig(b -> b.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) {
            var error = errors.getFirst();
            var location = error.getInstanceLocation();
            if (error.getProperty() != null && "required".equals(error.getKeyword())) {
                location = location.append(error.getProperty());
            }
            throw RenderFailure.input(location.toString(), error.getMessage());
        }
    }

    static void limit(JsonNode data, int bytes, boolean input) {
        if (data == null || !data.isObject() || !depth(data, 1) || JSON.writeValueAsBytes(data).length > bytes) {
            throw input ? RenderFailure.input("$", "JSON 根类型、深度或体积不符合限制")
                    : RenderFailure.template("Schema 或配置根类型、深度或体积不符合限制");
        }
    }

    private static boolean depth(JsonNode node, int level) {
        if (level > 32)
            return false;
        for (var child : node)
            if (!depth(child, level + 1))
                return false;
        return true;
    }
}
