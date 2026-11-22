package me.lendev.flow.docgen.render;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.config.ConfigureBuilder;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.deepoove.poi.policy.PictureRenderPolicy;
import com.deepoove.poi.policy.TableRenderPolicy;
import com.deepoove.poi.template.BlockTemplate;
import com.deepoove.poi.template.ChartTemplate;
import com.deepoove.poi.template.MetaTemplate;
import com.deepoove.poi.template.run.RunTemplate;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

import static me.lendev.flow.docgen.render.RenderFailure.require;

/**
 * 使用经过校验的本地数据与固定版本子模板生成 Word 文档。
 */
@Component
public final class DocxRenderer {
    /**
     * 模板和成品的 DOCX 文件字节上限。
     */
    public static final int MAX_DOCX_BYTES = OfficePackage.MAX_FILE;
    private static final String CHARTS = "charts";

    private static final class Prepared {
        private final byte[] bytes;
        private final JsonNode config;
        private final InputSchema schema;
        private final String guard;
        private final long bodyWeight;
        private final Map<String, Long> blockWeights;

        private Prepared(byte[] bytes, JsonNode config, InputSchema schema, String guard, TemplateLayout layout) {
            this.bytes = bytes;
            this.config = config;
            this.schema = schema;
            this.guard = guard;
            this.bodyWeight = layout.bodyWeight();
            this.blockWeights = layout.blockWeights();
        }
    }

    /**
     * 仅校验上传包的结构、关系与体积，允许草稿随后补齐配置。
     */
    public void validateFile(byte[] file) {
        new OfficePackage().inspect(file);
    }

    /**
     * 校验并统一模板的兼容级别与宋体字体，保留 poi-tl 标签及原生部件。
     *
     * @param file 上传或读取的模板 DOCX 字节
     * @return 统一格式的模板 DOCX 字节
     * @throws RenderFailure 文件格式、体积或内容不符合文档生成约束
     */
    public byte[] normalizeTemplate(byte[] file) {
        return WordFormat.normalize(file);
    }

    /**
     * 发布前检查模板标签、布局、Schema 与渲染配置的对应关系。
     */
    public void validate(Template root) {
        prepare(root);
    }

    /**
     * 在登记生成请求前校验输入对象、体积、深度及字段格式。
     */
    public void validateInput(Template root, JsonNode input) {
        validateInput(root.schema(), input);
    }

    /**
     * 使用版本数据库中的 Schema 校验数据，先于读取模板文件。
     */
    public void validateInput(JsonNode schema, JsonNode input) {
        new InputSchema(schema).validate(input);
    }

    /**
     * 生成 DOCX，同一请求复用已校验的子模板字节。
     */
    public byte[] render(Template root, JsonNode input, LongFunction<Template> children) {
        var session = new RenderSession(children);
        var prepared = prepare(root);
        session.budget.loaded(prepared.bytes.length);
        byte[] output = session.render(prepared, input);
        return WordFormat.normalize(output);
    }

    private static Prepared prepare(Template template) {
        var bytes = template.file();
        var parts = new OfficePackage().inspect(bytes);
        try {
            var root = template.schema();
            var schema = new InputSchema(root);
            var config = template.config();
            var layout = new TemplateLayout(schema, root, config);
            layout.validate(parts);
            try (var doc = XWPFTemplate.compile(new ByteArrayInputStream(bytes))) {
                String guard = config.path("subtemplates").isEmpty() ? "" : java.util.UUID.randomUUID().toString();
                for (var tag : doc.getElementTemplates()) mark(tag, guard);
                validateCharts(doc, config);
                if (!guard.isEmpty()) {
                    var output = new ByteArrayOutputStream();
                    doc.write(output);
                    bytes = output.toByteArray();
                }
                return new Prepared(bytes, config, schema, guard, layout);
            }
        } catch (RenderFailure e) {
            throw e;
        } catch (Exception e) {
            throw RenderFailure.template("模板、Schema 或渲染配置无法解析");
        }
    }

    private static void validateCharts(XWPFTemplate doc, JsonNode config) throws IOException, InvalidFormatException {
        var seen = new HashSet<String>();
        var workbooks = new HashSet<String>();
        var charts = config.path(CHARTS);
        int validated = 0;
        for (var tag : doc.getElementTemplates()) {
            if (!(tag instanceof ChartTemplate chartTag))
                continue;
            var key = chartTag.getTagName();
            require(charts.has(key), "原生图表未配置");
            seen.add(key);
            validated++;
            var chart = chartTag.getChart();
            var series = chart.getChartSeries();
            var type = ChartTypes.valueOf(charts.get(key).asString());
            require(series.size() == 1 && WordPolicies.ChartPolicy.validType(type, series.getFirst()),
                    "配置图表类型与样板不一致");
            int count = series.getFirst().getSeriesCount();
            require(count > 0 && count <= 10 && (type != ChartTypes.PIE || count == 1), "图表系列数量非法");
            var linked = chart.getPackagePart().getRelationshipsByType(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/package");
            require(linked.size() == 1, "图表须绑定一个工作簿");
            var workbookPart = chart.getPackagePart().getRelatedPart(linked.getRelationship(0));
            require(workbooks.add(workbookPart.getPartName().getName()), "每个图表须绑定独立工作簿");
            var book = chart.getWorkbook();
            require(book.getNumberOfSheets() == 1, "图表工作簿只能有一张数据表");
            var header = book.getSheetAt(0).getRow(0);
            require(header != null && header.getCell(0) != null, "图表工作簿缺少首行类目列");
            var label = header.getCell(0);
            require(label.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && !label.getStringCellValue().isBlank(),
                    "工作簿须有类目列名");
        }
        require(validated == doc.getXWPFDocument().getCharts().size(), "原生图表必须登记标签与工作簿");
        require(seen.containsAll(charts.propertyNames()), "配置缺少可绑定的原生图表");
    }

    /**
     * poi-tl 在 Include 时重新扫描整个父文档。只给上传模板中已解析的标签添加本次标记，
     * 使新插入的业务文本（包括子文档中展示的双大括号）在重扫时仍然是字面量。
     */
    private static void mark(MetaTemplate tag, String guard) {
        switch (tag) {
            case RunTemplate run -> {
                require(TemplateLayout.validPath(run.getTagName()) || "#this".equals(run.getTagName()),
                        "模板只支持字段路径");
                if (!guard.isEmpty())
                    run.getRun().setText(guard + run.getSource() + guard, 0);
            }
            case BlockTemplate block -> {
                mark(block.getStartMark(), guard);
                for (var child : block.getTemplates()) mark(child, guard);
                mark(block.getEndMark(), guard);
            }
            case ChartTemplate chart -> {
                var drawing = chart.getRun().getCTR().getDrawingArray(0);
                require(drawing.sizeOfInlineArray() == 1, "图表须为内联对象");
                var properties = drawing.getInlineArray(0).getDocPr();
                require(("{{" + chart.getTagName() + "}}").equals(properties.getDescr()), "图表标签应位于替代文字");
                if (!guard.isEmpty())
                    properties.setDescr(guard + properties.getDescr() + guard);
            }
            case null, default -> throw RenderFailure.template("图片只允许使用受控图片标签");
        }
    }

    /**
     * 同一次渲染及其子模板共享预算和模板缓存。
     */
    private static final class RenderSession {
        private final LongFunction<Template> loader;
        private final Map<Long, Prepared> cache = new HashMap<>();
        private final Set<Long> ancestry = new HashSet<>();
        private final RenderBudget budget = new RenderBudget();

        private RenderSession(LongFunction<Template> loader) {
            this.loader = loader;
        }

        private byte[] render(Prepared root, JsonNode input) {
            root.schema.validate(input);
            budget.markup(root.bodyWeight, "$");
            Map<String, Object> model = InputSchema.JSON.convertValue(input, new TypeReference<>() {
            });
            var config = configure(root);
            for (var entry : root.config.path("conditions").properties()) {
                Object value = lookup(model, entry.getValue().get("not").asString());
                put(model, entry.getKey(), Boolean.FALSE.equals(value));
            }
            for (var entry : root.config.path(CHARTS).properties()) {
                config.bind(entry.getKey(), new WordPolicies.ChartPolicy(entry.getValue().asString()));
            }
            for (var entry : root.config.path("subtemplates").properties()) {
                var path = entry.getKey();
                var version = TemplateLayout.version(entry.getValue());
                put(model, path, new WordPolicies.ChildDocs(renderChildren(version, path, input)));
            }
            return write(root, config, model);
        }

        private ConfigureBuilder configure(Prepared root) {
            var config = Configure.builder()
                    .buildGramer(root.guard + "{{", "}}" + root.guard)
                    .setRenderDataComputeFactory(env -> key -> budgetedLookup(root, env.getRoot(), key));
            var picture = new PictureRenderPolicy();
            var table = new TableRenderPolicy();
            config.addPlugin(
                    '@', (tag, data, doc) -> picture.render(tag, Media.picture(tag.getTagName(), data, budget), doc));
            config.addPlugin('#', (tag, data, doc) -> table.render(tag, Media.table(tag.getTagName(), data, budget), doc));
            config.addPlugin('+', new WordPolicies.ChildPolicy());
            for (var entry : root.config.path("rowLoops").properties()) {
                var rule = entry.getValue();
                config.bind(entry.getKey(),
                        rule.isEmpty() ? new LoopRowTableRenderPolicy()
                                : new WordPolicies.GroupLoop(
                                rule.get("groupBy").asString(), rule.get("mergeColumn").asInt()));
            }
            return config;
        }

        private Object budgetedLookup(Prepared root, Object data, String key) {
            Object value = lookup(data, key);
            if (value instanceof List<?> list) {
                budget.loop(list.size(), key);
                budget.markup(root.blockWeights.getOrDefault(key, 0L) * list.size(), key);
            }
            if (value instanceof String text)
                budget.markup(2L * text.length(), key);
            return value;
        }

        private List<byte[]> renderChildren(long version, String path, JsonNode input) {
            require(ancestry.size() < 2 && ancestry.add(version), "子模板引用存在环或超过两层");
            try {
                var child = cache.computeIfAbsent(version, this::loadChild);
                var data = input.at("/" + path.replace('.', '/'));
                if (data.isNull() || data.isMissingNode()) {
                    return List.of();
                }
                budget.loop(data.isArray() ? data.size() : 1, path);
                Iterable<JsonNode> items = data.isArray() ? data : List.of(data);
                List<byte[]> documents = new ArrayList<>();
                for (var row : items) {
                    byte[] bytes = render(child, row);
                    budget.intermediate(bytes.length);
                    documents.add(bytes);
                }
                return documents;
            } finally {
                ancestry.remove(version);
            }
        }

        private Prepared loadChild(long id) {
            var template = loader.apply(id);
            require(template != null, "子模板版本不可用");
            var prepared = prepare(template);
            budget.loaded(prepared.bytes.length);
            return prepared;
        }

        private static byte[] write(Prepared root, ConfigureBuilder config, Map<String, Object> model) {
            try (var doc = XWPFTemplate.compile(new ByteArrayInputStream(root.bytes), config.build())) {
                doc.render(model);
                var output = new LimitedOutput();
                doc.write(output);
                return output.toByteArray();
            } catch (Exception e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause())
                    if (cause instanceof RenderFailure failure)
                        throw failure;
                throw new RenderFailure("RENDER_FAILED", "$", "无法根据此模板生成文档");
            }
        }

        @SuppressWarnings("unchecked")
        private static void put(Map<String, Object> model, String key, Object value) {
            String[] path = key.split("\\.");
            for (int i = 0; i < path.length - 1; i++) {
                Object nested = model.computeIfAbsent(path[i], ignored -> new HashMap<String, Object>());
                if (!(nested instanceof Map<?, ?> map))
                    return;
                model = (Map<String, Object>) map;
            }
            model.put(path[path.length - 1], value);
        }
    }

    static Object lookup(Object data, String key) {
        if ("#this".equals(key))
            return data;
        for (String part : key.split("\\.")) {
            if (!(data instanceof Map<?, ?> map))
                return null;
            data = map.get(part);
        }
        return data;
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        private void check(int bytes) {
            if (bytes > MAX_DOCX_BYTES - count)
                throw new RenderFailure("FILE_TOO_LARGE", "$", "生成文件超过大小上限");
        }

        @Override
        public synchronized void write(int value) {
            check(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int off, int len) {
            check(len);
            super.write(bytes, off, len);
        }
    }
}
