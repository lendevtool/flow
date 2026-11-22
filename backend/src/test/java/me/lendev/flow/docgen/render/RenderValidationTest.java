package me.lendev.flow.docgen.render;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderValidationTest {
    private final DocxRenderer renderer = new DocxRenderer();
    private final JsonMapper json = new JsonMapper();

    private static byte[] document(String text) throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void rejectsUnknownFieldsAndExpressionsBeforePublishing() throws Exception {
        var schema = json.readTree("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}");
        for (var tag : java.util.List.of("{{missing}}", "{{title.toString()}}", "{{?title}}")) {
            var template = new Template(document(tag), schema, json.createObjectNode());
            assertThatThrownBy(() -> renderer.validate(template))
                    .isInstanceOfSatisfying(
                            RenderFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_TEMPLATE"));
        }
    }

    @Test
    void resolvesLocalSchemaAndEnforcesDateFormatWithPath() throws Exception {
        var schema = json.readTree("""
                {"type":"object","properties":{"date":{"$ref":"#/$defs/date"}},"$defs":{"date":{"type":"string","format":"date"}}}
                """);
        var template = new Template(document("{{date}}"), schema, json.createObjectNode());
        renderer.validate(template);
        var valid = json.readTree("{\"date\":\"2024-02-29\"}");
        var invalid = json.readTree("{\"date\":\"2025-02-29\"}");
        renderer.validateInput(template, valid);
        assertThatThrownBy(() -> renderer.validateInput(template, invalid))
                .isInstanceOfSatisfying(RenderFailure.class, e -> {
                    assertThat(e.path()).contains("date");
                    assertThat(e.code()).isEqualTo("INVALID_INPUT");
                });
    }

    @Test
    void rejectsRemoteSchemaAndExternalRelationships() throws Exception {
        var schema = json.readTree(
                "{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"https://example.org/schema\"}}}");
        var template = new Template(document("{{title}}"), schema, json.createObjectNode());
        assertThatThrownBy(() -> renderer.validate(template)).isInstanceOf(RenderFailure.class);
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createHyperlinkRun("https://example.org/").setText("外部参考");
            doc.write(out);
            var bytes = out.toByteArray();
            assertThatThrownBy(() -> renderer.validateFile(bytes)).isInstanceOf(RenderFailure.class);
        }
    }

    @Test
    void preservesLiteralTemplateNotationInUserTextWhenMergingChildren() throws Exception {
        var folder = Path.of(getClass().getResource("/document-generation-samples/05-subtemplates").toURI());
        var metadata = json.readTree(folder.resolve("template.json").toFile());
        var config = json.readTree("{\"subtemplates\":{\"teams\":{\"templateVersionId\":\"1\"}}}");
        var root =
                new Template(Files.readAllBytes(folder.resolve("template.docx")), metadata.get("inputSchema"), config);
        var child = new Template(
                document("{{team}}"), metadata.at("/inputSchema/properties/teams/items"), json.createObjectNode());
        var input = (ObjectNode) json.readTree(folder.resolve("single.json").toFile());
        input.put("title", "填写说明 {{+teams}}");
        ((ObjectNode) input.get("teams").get(0)).put("team", "样例说明");
        var bytes = renderer.render(root, input, id -> child);
        try (var doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            var lines = doc.getParagraphs().stream().map(p -> p.getText()).toList();
            assertThat(lines).contains("填写说明 {{+teams}}");
            assertThat(lines.stream().filter("样例说明"::equals)).hasSize(1);
        }
    }

    @Test
    void refusesOversizedFilesAndWrongFormat() {
        var large = new byte[OfficePackage.MAX_FILE + 1];
        var invalid = new byte[]{1, 2, 3};
        assertThatThrownBy(() -> renderer.validateFile(large))
                .isInstanceOfSatisfying(RenderFailure.class, e -> assertThat(e.code()).isEqualTo("FILE_TOO_LARGE"));
        assertThatThrownBy(() -> renderer.validateFile(invalid))
                .isInstanceOfSatisfying(
                        RenderFailure.class, e -> assertThat(e.code()).isEqualTo("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void appliesPerLoopAndWholeRequestLimits() throws Exception {
        var folder = Path.of(getClass().getResource("/document-generation-samples/03-sections").toURI());
        var meta = json.readTree(folder.resolve("template.json").toFile());
        var root = new Template(
                Files.readAllBytes(folder.resolve("template.docx")), meta.get("inputSchema"), meta.get("renderConfig"));
        var input = (ObjectNode) json.readTree(folder.resolve("multiple.json").toFile());
        var chapters = input.putArray("chapters");
        var chapter = json.readTree(folder.resolve("single.json").toFile()).path("chapters").get(0);
        for (int i = 0; i < 501; i++) chapters.add(chapter);
        assertThatThrownBy(() -> renderer.render(root, input, id -> null))
                .isInstanceOfSatisfying(
                        RenderFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_RENDER_DATA"));
        var budget = new RenderBudget();
        for (int i = 0; i < 4; i++) budget.loop(500, "rows");
        assertThatThrownBy(() -> budget.loop(1, "rows")).isInstanceOf(RenderFailure.class);
    }

    @Test
    void emptyGroupingKeysNeverMerge() throws Exception {
        var folder = Path.of(getClass().getResource("/document-generation-samples/02-tables").toURI());
        var meta = json.readTree(folder.resolve("template.json").toFile());
        var root = new Template(
                Files.readAllBytes(folder.resolve("template.docx")), meta.get("inputSchema"), meta.get("renderConfig"));
        var input = (ObjectNode) json.readTree(folder.resolve("multiple.json").toFile());
        for (var row : input.get("groupRows")) ((ObjectNode) row).put("group", "");
        var bytes = renderer.render(root, input, id -> null);
        try (var doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            for (var row : doc.getTables().get(3).getRows()) {
                assertThat(row.getCell(0).getCTTc().getTcPr().isSetVMerge()).isFalse();
            }
        }
    }

    @Test
    void rejectsInvalidMediaDataAndChartDimensions() throws Exception {
        var folder = Path.of(getClass().getResource("/document-generation-samples/04-media").toURI());
        var metadata = json.readTree(folder.resolve("template.json").toFile());
        var template = new Template(Files.readAllBytes(folder.resolve("template.docx")), metadata.get("inputSchema"),
                metadata.get("renderConfig"));
        var image = (ObjectNode) json.readTree(folder.resolve("png.json").toFile());
        var chart = image.deepCopy();
        ((ObjectNode) image.get("logo")).put("mediaType", "image/jpeg");
        ((ObjectNode) chart.at("/activityBar/series/0")).putArray("values").add(1);
        for (var input : java.util.List.of(image, chart)) {
            assertThatThrownBy(() -> renderer.render(template, input, id -> null))
                    .isInstanceOfSatisfying(
                            RenderFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_RENDER_DATA"));
        }
    }
}
