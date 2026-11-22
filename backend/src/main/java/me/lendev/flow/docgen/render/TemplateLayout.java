package me.lendev.flow.docgen.render;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static me.lendev.flow.docgen.render.InputSchema.ITEMS;
import static me.lendev.flow.docgen.render.RenderFailure.require;

/**
 * 按段落、块及样板行的局部作用域检查标签。
 */
final class TemplateLayout {
    private static final Pattern ROW_FIELD = Pattern.compile("\\[([^\\[\\]]+)\\]");
    private static final String ROW_LOOPS = "rowLoops";
    private static final String CONDITIONS = "conditions";
    private static final String SUBTEMPLATES = "subtemplates";
    private static final String CHARTS = "charts";
    private static final Set<String> SECTIONS = Set.of(ROW_LOOPS, CONDITIONS, SUBTEMPLATES, CHARTS);
    private static final String OBJECT = "object";
    private static final String ARRAY = "array";
    private static final String MERGE_COLUMN = "mergeColumn";
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final InputSchema schema;
    private final JsonNode root;
    private final JsonNode config;
    private final Set<String> matched = new HashSet<>();
    private final Map<Node, JsonNode> sampleRows = new java.util.IdentityHashMap<>();
    private final ArrayDeque<Block> scopes = new ArrayDeque<>();
    private final Map<String, Long> blockWeights = new java.util.HashMap<>();
    private long bodyWeight;

    private record Block(String tag, JsonNode value, Node start) {
    }

    long bodyWeight() {
        return bodyWeight;
    }

    Map<String, Long> blockWeights() {
        return Map.copyOf(blockWeights);
    }

    private static long weight(Node node) {
        long bytes = 80L;
        if (node.getNodeValue() != null)
            bytes += 2L * node.getNodeValue().length();
        if (node.getAttributes() != null)
            bytes += 80L * node.getAttributes().getLength();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) bytes += weight(child);
        return bytes;
    }

    TemplateLayout(InputSchema schema, JsonNode root, JsonNode config) {
        this.schema = schema;
        this.root = root;
        this.config = config;
    }

    void validate(Map<String, Document> documents) {
        InputSchema.limit(config, 64 * 1024, false);
        require(SECTIONS.containsAll(config.propertyNames()), "未知渲染配置");
        var configured = new HashSet<String>();
        for (var section : config.properties()) {
            require(section.getValue().isObject(), "渲染配置必须为对象");
            for (var entry : section.getValue().properties()) {
                String key = entry.getKey();
                require(validPath(key) && configured.add(key), "渲染配置路径非法或重复");
                validateRule(section.getKey(), key, entry.getValue());
            }
        }
        for (var part : documents.entrySet()) {
            if (!part.getKey().matches("word/(?:document|header\\d*|footer\\d*|footnotes|endnotes)\\.xml"))
                continue;
            bodyWeight += weight(part.getValue().getDocumentElement());
            var paragraphs = part.getValue().getElementsByTagNameNS(WORD, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) inspectParagraph((Element) paragraphs.item(i));
            require(scopes.isEmpty(), "章节标签未闭合");
            var drawings = part.getValue().getElementsByTagNameNS("*", "docPr");
            for (int i = 0; i < drawings.getLength(); i++) {
                String alt = ((Element) drawings.item(i)).getAttribute("descr");
                if (!alt.contains("{{"))
                    continue;
                require(alt.startsWith("{{") && alt.endsWith("}}") && alt.length() > 4, "非法图表标签");
                String key = alt.substring(2, alt.length() - 2);
                require(config.path(CHARTS).has(key), "图表必须有固定配置");
                matched.add(key);
            }
        }
        require(matched.containsAll(configured), "配置引用了未在模板中出现的标签");
    }

    private void validateRule(String section, String key, JsonNode rule) {
        JsonNode field = schema.field(root, key);
        switch (section) {
            case CONDITIONS -> {
                require(rule.isObject() && rule.size() == 1 && rule.path("not").isString() && field.isMissingNode()
                                && validParent(key),
                        "派生条件必须使用新字段");
                String path = rule.get("not").asString();
                require(validPath(path) && typed(schema.field(root, path), "boolean"), "条件来源须为布尔字段");
            }
            case CHARTS -> require(typed(field, OBJECT) && Set.of("BAR", "LINE", "PIE").contains(rule.asString("")),
                    "图表类型非法");
            case ROW_LOOPS -> {
                require(rule.isObject() && typed(field, ARRAY), "行循环必须引用数组");
                if (rule.isEmpty())
                    return;
                JsonNode mergeColumn = rule.path(MERGE_COLUMN);
                require(rule.size() == 2 && rule.path("groupBy").isString()
                                && mergeColumn.isIntegralNumber()
                                && mergeColumn.canConvertToInt() && mergeColumn.asInt() >= 0,
                        "分组规则非法");
                String path = rule.get("groupBy").asString();
                require(validPath(path) && !schema.field(field.path(ITEMS), path).isMissingNode(), "分组字段不存在");
            }
            case SUBTEMPLATES -> {
                require((typed(field, OBJECT) || typed(field, ARRAY)) && rule.isObject() && rule.size() == 1,
                        "子模板配置无效");
                version(rule);
            }
            default -> throw RenderFailure.template("不支持的配置");
        }
    }

    private boolean validParent(String key) {
        int lastDot = key.lastIndexOf('.');
        return lastDot < 0 || typed(schema.field(root, key.substring(0, lastDot)), OBJECT);
    }

    static long version(JsonNode rule) {
        var id = rule.path("templateVersionId");
        try {
            long value = Long.parseLong(id.asString(""));
            require(value > 0, "子模板版本非法");
            return value;
        } catch (NumberFormatException e) {
            throw RenderFailure.template("子模板版本非法");
        }
    }

    private void inspectParagraph(Element p) {
        String text = paragraphText(p);
        JsonNode context = scopes.isEmpty() ? root : scopes.peek().value;
        int pos = 0;
        while (pos < text.length()) {
            int start = text.indexOf("{{", pos);
            int end = text.indexOf("}}", pos);
            if (start < 0 && end < 0)
                break;
            require(start >= 0 && end > start + 2, "模板标签未配对");
            String tag = text.substring(start + 2, end);
            char op = tag.charAt(0);
            String path = "?/#@+=".indexOf(op) >= 0 ? tag.substring(1) : tag;
            if (tag.equals("=#this")) {
                require(!scopes.isEmpty() && !typed(context, OBJECT), "当前元素标签只可用于标量列表");
            } else {
                require(validPath(path), "标签只接受点分字段路径");
                inspectTag(p, text, context, tag, op, path);
            }
            pos = end + 2;
        }
        inspectSampleRow(p, text);
    }

    private void inspectSampleRow(Element p, String text) {
        JsonNode itemSchema = sampleRows.get(ancestor(p, "tr"));
        if (itemSchema != null) {
            var field = ROW_FIELD.matcher(text);
            while (field.find()) {
                String path = field.group(1);
                require(validPath(path) && !schema.field(itemSchema, path).isMissingNode(),
                        "样板行字段未在 Schema 声明");
            }
        }
    }

    private void inspectTag(Element p, String text, JsonNode context, String tag, char op, String path) {
        if (op == '/' || op == '?' || op == '+')
            require(text.strip().equals("{{" + tag + "}}"), "章节及子模板标签应独占段落");
        if (op == '/') {
            closeBlock(p, path);
            return;
        }
        JsonNode field = schema.field(context, path);
        boolean derived = config.path(CONDITIONS).has(path);
        require(!field.isMissingNode() || derived && op == '?', "Schema 中未声明标签 " + path);
        if (op == '?') {
            openBlock(p, context, path, field, derived);
        } else if (op == '+') {
            require(config.path(SUBTEMPLATES).has(path) && "body".equals(p.getParentNode().getLocalName())
                            && scopes.isEmpty(),
                    "子模板必须位于正文顶层独立段落");
            matched.add(path);
        } else if (config.path(ROW_LOOPS).has(path)) {
            inspectRowLoop(p, field, tag, path);
        } else if (op == '@' || op == '#') {
            require(typed(field, OBJECT), "图片和统计表必须绑定对象");
        } else {
            require(op != '=' && !typed(field, OBJECT) && !typed(field, ARRAY), "普通标签仅可绑定标量");
        }
    }

    private void closeBlock(Element p, String path) {
        require(!scopes.isEmpty() && scopes.peek().tag.equals(path)
                        && scopes.peek().start.getParentNode() == p.getParentNode(),
                "章节标签未配对");
        var opening = scopes.pop().start;
        long bytes = 0;
        for (var node = opening.getNextSibling(); node != p; node = node.getNextSibling()) bytes += weight(node);
        blockWeights.merge(path, bytes, Math::max);
    }

    private void openBlock(Element p, JsonNode context, String path, JsonNode field, boolean derived) {
        boolean array = typed(field, ARRAY);
        require(derived || array || typed(field, "boolean"), "章节需绑定数组或布尔值");
        if (derived)
            matched.add(path);
        require(scopes.size() < 32, "章节嵌套过深");
        scopes.push(new Block(path, array ? schema.resolve(field.path(ITEMS)) : context, p));
    }

    private void inspectRowLoop(Element p, JsonNode field, String tag, String path) {
        Node row = ancestor(p, "tr");
        Node sample = row == null ? null : row.getNextSibling();
        while (sample != null && !(sample instanceof Element)) sample = sample.getNextSibling();
        require(sample != null && "tr".equals(sample.getLocalName()), "缺少循环样板行");
        require(validPath(tag) && !sampleRows.containsKey(sample), "每个样板行只接受一个独立循环标签");
        sampleRows.put(sample, field.path(ITEMS));
        blockWeights.merge(path, weight(sample), Math::max);
        var rule = config.path(ROW_LOOPS).path(path);
        if (rule.has(MERGE_COLUMN)) {
            var cells = ((Element) sample).getElementsByTagNameNS(WORD, "tc");
            var cell = (Element) cells.item(rule.get(MERGE_COLUMN).asInt());
            require(cell != null && cell.getElementsByTagNameNS(WORD, "gridSpan").getLength() == 0
                            && cell.getElementsByTagNameNS(WORD, "vMerge").getLength() == 0,
                    "分组合并列必须是独立单元格");
        }
        matched.add(path);
    }

    private static Node ancestor(Node n, String name) {
        while (n != null && !name.equals(n.getLocalName())) n = n.getParentNode();
        return n;
    }

    private static String paragraphText(Element p) {
        var result = new StringBuilder();
        var runs = p.getElementsByTagNameNS(WORD, "t");
        for (int i = 0; i < runs.getLength(); i++) result.append(runs.item(i).getTextContent());
        return result.toString();
    }

    static boolean validPath(String path) {
        if (path.length() > 256) {
            return false;
        }
        boolean segmentStart = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (segmentStart) {
                if (c < 'a' || c > 'z') {
                    return false;
                }
            } else if (c != '.' && !isAsciiLetterOrDigit(c)) {
                return false;
            }
            segmentStart = c == '.';
        }
        return !segmentStart;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    private static boolean typed(JsonNode node, String type) {
        return type.equals(node.path("type").asString(""));
    }
}
