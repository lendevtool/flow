package me.lendev.flow.docgen.render;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocxRendererTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";

    @TestFactory
    List<DynamicTest> renderAllSamples() throws Exception {
        var root = Path.of(getClass().getResource("/document-generation-samples").toURI());
        var paths = new HashMap<String, Long>();
        var templates = new HashMap<Long, Template>();
        var tests = new ArrayList<DynamicTest>();
        try (var files = Files.walk(root)) {
            var metadata = files.filter(f -> f.endsWith("template.json")).sorted().toList();
            for (var file : metadata) {
                paths.put(root.relativize(file.getParent()).toString(), (long) paths.size() + 1);
            }
            for (var file : metadata) {
                var folder = file.getParent();
                var meta = JSON.readTree(file.toFile());
                var config = (ObjectNode) meta.get("renderConfig");
                for (var entry : meta.path("sampleChildTemplates").properties()) {
                    config.withObjectProperty("subtemplates")
                            .putObject(entry.getKey())
                            .put("templateVersionId", paths.get(entry.getValue().asString()).toString());
                }
                var template = new Template(
                        Files.readAllBytes(folder.resolve("template.docx")), meta.get("inputSchema"), config);
                templates.put(paths.get(root.relativize(folder).toString()), template);
                try (var samples = Files.list(folder)) {
                    for (var sample :
                            samples.filter(p -> p.toString().endsWith(".json") && !p.equals(file)).sorted().toList()) {
                        tests.add(DynamicTest.dynamicTest(root.relativize(sample).toString(),
                                () -> verify(template, JSON.readTree(sample.toFile()), templates)));
                    }
                }
            }
        }
        assertThat(templates).hasSize(9);
        assertThat(tests).hasSize(18);
        return tests;
    }

    private void verify(Template template, JsonNode input, Map<Long, Template> templates) throws Exception {
        var result = new DocxRenderer().render(template, input, templates::get);
        assertDocumentFormat(result);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result));
             var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
            assertThat(doc.getSettings().getCTSettings().isSetUpdateFields()).isFalse();
            var text = extractor.getText();
            assertThat(text).doesNotContain("{{", "[name]", "[group]", "[quantity]");
            if (input.has("title"))
                assertThat(text).contains(input.get("title").asString());

            if (input.has("caseInfo")) {
                for (var field : input.get("caseInfo")) assertThat(text).contains(field.asString());
                for (var line : input.get("notes").asString().lines().toList()) assertThat(text).contains(line);
            }
            if (input.has("wideRows")) {
                var tables = doc.getTables();
                assertThat(tables).hasSize(5);
                assertThat(tables.get(0).getRows()).hasSize(input.get("items").size() + 2);
                assertThat(tables.get(0).getRows().getLast().getCell(0).getText()).isEqualTo("明细结束");
                assertThat(tables.get(1).getRows()).hasSize(input.get("wideRows").size() + 1);
                for (var row : tables.get(1).getRows()) {
                    assertThat(row.getCell(1).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(3);
                }
                var fixed = tables.get(2).getRows();
                assertThat(fixed.get(0).getCell(0).getCTTc().getTcPr().getVMerge().getVal()).hasToString("restart");
                var continuation = fixed.get(1).getCell(0).getCTTc().getTcPr().getVMerge();
                assertThat(continuation).isNotNull();
                assertThat(String.valueOf(continuation.getVal())).isIn("null", "continue");
                var dynamic = tables.get(4);
                var summary = input.get("summary");
                assertThat(dynamic.getRows()).hasSize(summary.get("rows").size() + 1);
                for (int i = 0; i < dynamic.getRows().size(); i++) {
                    var expected = i == 0 ? summary.get("headers") : summary.get("rows").get(i - 1);
                    var cells = dynamic.getRow(i).getTableCells();
                    assertThat(cells).hasSize(expected.size());
                    for (int j = 0; j < cells.size(); j++)
                        assertThat(cells.get(j).getText()).isEqualTo(expected.get(j).asString());
                }
            }
            if (input.has("chapters")) {
                for (var chapter : input.get("chapters")) {
                    assertThat(text).contains(chapter.get("heading").asString(), chapter.get("summary").asString());
                    for (var entry : chapter.get("entries")) assertThat(text).contains(entry.get("content").asString());
                }
                if (input.get("showAppendix").asBoolean())
                    assertThat(text).contains(input.get("appendixText").asString());
                else
                    assertThat(text).contains("本次报告没有附录");
            }
            if (input.has("archiveOwner"))
                for (var item : input.get("items")) assertThat(text).contains(item.asString());
            if (input.has("groupRows")) {
                var table = doc.getTables().get(input.has("fixedRows") ? 3 : 0);
                var rows = input.get("groupRows");
                assertThat(table.getRows()).hasSize(rows.size() + 1);
                for (int i = 0; i < rows.size(); i++) {
                    String group = rows.get(i).get("group").asString();
                    boolean previous = i > 0 && rows.get(i - 1).get("group").asString().equals(group);
                    boolean next = i + 1 < rows.size() && rows.get(i + 1).get("group").asString().equals(group);
                    var cell = table.getRow(i + 1).getCell(0);
                    var merge = cell.getCTTc().getTcPr().getVMerge();
                    assertThat(cell.getText()).isEqualTo(previous ? "" : group);
                    assertThat(merge == null ? "" : merge.getVal().toString())
                            .isEqualTo(previous ? "continue"
                                    : next ? "restart"
                                    : "");
                }
            }
            if (input.has("logo")) {
                assertThat(doc.getAllPictures().getFirst().getData())
                        .isEqualTo(java.util.Base64.getDecoder().decode(input.at("/logo/base64").asString()));
            }
            assertThat(doc.getCharts()).hasSize(template.config().path("charts").size());
            for (var key : template.config().path("charts").propertyNames()) {
                var data = input.get(key);
                var chart = doc.getCharts()
                        .stream()
                        .filter(c
                                -> c.getCTChart()
                                .getTitle()
                                .getTx()
                                .getRich()
                                .getPArray(0)
                                .getRArray(0)
                                .getT()
                                .equals(data.get("title").asString()))
                        .findFirst()
                        .orElseThrow();
                var plot = chart.getChartSeries().getFirst();
                var sheet = chart.getWorkbook().getSheetAt(0);
                assertThat(plot.getSeriesCount()).isEqualTo(data.get("series").size());
                for (int j = 0; j < plot.getSeriesCount(); j++) {
                    for (int i = 0; i < data.get("categories").size(); i++) {
                        var label = data.get("categories").get(i).asString();
                        var value = data.get("series").get(j).get("values").get(i).asDouble();
                        assertThat(plot.getSeries(j).getCategoryData().getPointAt(i)).isEqualTo(label);
                        assertThat(plot.getSeries(j).getValuesData().getPointAt(i).doubleValue()).isEqualTo(value);
                        assertThat(sheet.getRow(i + 1).getCell(j + 1).getNumericCellValue()).isEqualTo(value);
                    }
                }
            }
            if (input.has("reportCode")) {
                assertThat(doc.getHeaderList().getFirst().getText()).contains(input.get("reportCode").asString());
                assertThat(doc.getFooterList().getFirst()._getHdrFtr().xmlText())
                        .contains("PAGE", "NUMPAGES").doesNotContain(":dirty=");
                assertThat(doc.getDocument().xmlText()).contains("TOC").doesNotContain(":dirty=");
            }
            for (var team : input.path("teams")) {
                assertThat(text).contains(team.get("team").asString());
                for (var member : team.path("members")) assertThat(text).contains(member.get("name").asString());
            }
        }
    }

    static void assertDocumentFormat(byte[] file) {
        var parts = new OfficePackage().inspect(file);
        var compatibility = parts.get("word/settings.xml").getElementsByTagNameNS(WORD, "compatSetting");
        var mode = new ArrayList<String>();
        for (int i = 0; i < compatibility.getLength(); i++) {
            var setting = (org.w3c.dom.Element) compatibility.item(i);
            if (setting.getAttributeNS(WORD, "name").equals("compatibilityMode"))
                mode.add(setting.getAttributeNS(WORD, "val"));
        }
        assertThat(mode).containsExactly("15");
        assertThat(parts.get("word/styles.xml").getElementsByTagNameNS(WORD, "docDefaults").getLength()).isEqualTo(1);
        for (var entry : parts.entrySet()) {
            if (!entry.getKey().startsWith("word/")) continue;
            var fonts = entry.getValue().getElementsByTagNameNS(WORD, "rFonts");
            for (int i = 0; i < fonts.getLength(); i++) {
                var font = (org.w3c.dom.Element) fonts.item(i);
                for (var script : List.of("ascii", "hAnsi", "eastAsia", "cs"))
                    assertThat(font.getAttributeNS(WORD, script)).as(entry.getKey()).isEqualTo("SimSun");
                for (var theme : List.of("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme"))
                    assertThat(font.hasAttributeNS(WORD, theme)).as(entry.getKey()).isFalse();
            }
            if (entry.getKey().startsWith("word/charts/") && !entry.getKey().contains("/_rels/")) {
                var chart = entry.getValue().getElementsByTagNameNS(DRAWING, "latin");
                assertThat(chart.getLength()).as(entry.getKey()).isPositive();
                for (int i = 0; i < chart.getLength(); i++)
                    assertThat(((org.w3c.dom.Element) chart.item(i)).getAttribute("typeface")).isEqualTo("SimSun");
            }
        }
    }
}
