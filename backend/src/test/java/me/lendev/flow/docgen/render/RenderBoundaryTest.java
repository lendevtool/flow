package me.lendev.flow.docgen.render;

import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.util.Units;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderBoundaryTest {
    private static final int MIB = 1024 * 1024;
    private static final JsonMapper JSON = new JsonMapper();
    private final DocxRenderer renderer = new DocxRenderer();

    private static byte[] document(String... paragraphs) throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) doc.createParagraph().createRun().setText(paragraph);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void fails(String code, Executable action) {
        assertThat(assertThrows(RenderFailure.class, action).code()).isEqualTo(code);
    }

    @Test
    void fixedVersionSnapshotsKeepOriginalBytesSchemaAndConfiguration() throws Exception {
        var file = document("{{name}}");
        var schema =
                (ObjectNode) JSON.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        var config = JSON.createObjectNode();
        var version = new Template(file, schema, config);
        var same = new Template(file, schema, null);
        Arrays.fill(file, (byte) 0);
        schema.removeAll();
        config.put("unexpected", true);
        Arrays.fill(version.file(), (byte) 1);
        ((ObjectNode) version.schema()).removeAll();
        ((ObjectNode) version.config()).put("unexpected", true);
        assertThat(version).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(version.toString()).doesNotContain("properties", "{{name}}");
        renderer.validate(version);
    }

    @Test
    void typedAdditionalFieldsAndNestedPresenceAreValidatedAtTheirInputPath() throws Exception {
        var schema = JSON.readTree("""
                {"type":"object","properties":{"values":{"type":"object","additionalProperties":{"type":"integer"}}},"required":["values"]}
                """);
        var root = new Template(document("统计"), schema, null);
        var valid = JSON.readTree("{\"values\":{\"quantity\":4}}");
        renderer.validateInput(root, valid);
        var wrong = JSON.readTree("{\"values\":{\"quantity\":\"四\"}}");
        var error = assertThrows(RenderFailure.class, () -> renderer.validateInput(root, wrong));
        assertThat(error.code()).isEqualTo("INVALID_INPUT");
        assertThat(error.path()).contains("values", "quantity");
        var absent = JSON.createObjectNode();
        var missing = assertThrows(RenderFailure.class, () -> renderer.validateInput(root, absent));
        assertThat(missing.path()).contains("values");
    }

    @Test
    void enforcesIndependentDepthAndSizeBoundariesForInputSchemaAndConfig() throws Exception {
        var schema = JSON.readTree("{\"type\":\"object\"}");
        var file = document("复核");
        var root = new Template(file, schema, null);
        var nested = JSON.createObjectNode();
        var cursor = nested;
        for (int i = 0; i < 30; i++) cursor = cursor.putObject("record");
        cursor.put("amount", 3);
        renderer.validateInput(root, nested);
        cursor.putObject("extra").put("amount", 7);
        fails("INVALID_INPUT", () -> renderer.validateInput(root, nested));
        var input = JSON.createObjectNode().put("memo", "A".repeat(4 * MIB));
        fails("INVALID_INPUT", () -> renderer.validateInput(root, input));
        var largeSchema = ((ObjectNode) schema.deepCopy()).put("description", "A".repeat(128 * 1024));
        var badSchema = new Template(file, largeSchema, null);
        fails("INVALID_TEMPLATE", () -> renderer.validate(badSchema));
        var bigConfig = JSON.createObjectNode();
        bigConfig.putObject("conditions").put("flag", "A".repeat(64 * 1024));
        var badConfig = new Template(file, schema, bigConfig);
        fails("INVALID_TEMPLATE", () -> renderer.validate(badConfig));
    }

    @Test
    void optionalMediaAndStatisticsLeaveNoPlaceholderWhenMissing() throws Exception {
        var root = new Template(document("正文", "{{@image}}", "{{#totals}}"), JSON.readTree("""
                {"type":"object","properties":{"image":{"type":"object"},"totals":{"type":"object"}}}
                """), null);
        byte[] file = renderer.render(root, JSON.createObjectNode(), id -> null);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(file))) {
            assertThat(doc.getDocument().xmlText()).contains("正文").doesNotContain("{{");
            assertThat(doc.getTables()).isEmpty();
            assertThat(doc.getAllPictures()).isEmpty();
        }
    }

    @Test
    void rejectsInconsistentStatisticsTablesEvenWithPermissiveSchema() throws Exception {
        var root = new Template(document("{{#totals}}"),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"totals\":{\"type\":\"object\"}}}"), null);
        for (String fields : List.of("{\"headers\":[],\"rows\":[]}", "{\"headers\":[\"金额\"],\"rows\":[[1,2]]}",
                "{\"headers\":[\"金额\"],\"rows\":[[{\"currency\":\"CNY\"}]]}")) {
            var input = JSON.createObjectNode().set("totals", JSON.readTree(fields));
            fails("INVALID_RENDER_DATA", () -> renderer.render(root, input, id -> null));
        }
    }

    @Test
    void enforcesImageDisplayEncodedAndDecodedByteLimitsBeforeRendering() throws Exception {
        var root = new Template(document("{{@image}}"),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"image\":{\"type\":\"object\"}}}"), null);
        var sample = Path.of(getClass().getResource("/document-generation-samples/04-media/png.json").toURI());
        var png = (ObjectNode) JSON.readTree(sample.toFile()).get("logo");
        for (var image : List.of(png.deepCopy().put("width", 4097), png.deepCopy().put("base64", "AA=="),
                png.deepCopy().put("base64", "A".repeat(1_398_108)),
                png.deepCopy().put("base64", "A".repeat(1_398_104)))) {
            var input = JSON.createObjectNode().set("image", image);
            fails("INVALID_RENDER_DATA", () -> renderer.render(root, input, id -> null));
        }
    }

    @Test
    void derivesConditionsUnderAnOptionalParentWithoutChangingTheSubmittedJson() throws Exception {
        var root = new Template(document("{{?flags.hidden}}", "请复核", "{{/flags.hidden}}", "{{absent.note}}"),
                JSON.readTree("""
                        {"type":"object","properties":{"show":{"type":"boolean"},"flags":{"type":"object"},
                          "absent":{"type":"object","properties":{"note":{"type":"string"}}}}}
                        """), JSON.readTree("{\"conditions\":{\"flags.hidden\":{\"not\":\"show\"}}}"));
        var input = JSON.readTree("{\"show\":false}");
        var output = renderer.render(root, input, id -> null);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(output))) {
            assertThat(doc.getDocument().xmlText()).contains("请复核").doesNotContain("{{");
        }
        assertThat(input.has("flags")).isFalse();
    }

    @Test
    void publishingRequiresANumericFixedChildVersion() throws Exception {
        var root = new Template(document("{{+team}}"),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"team\":{\"type\":\"object\"}}}"),
                JSON.readTree("{\"subtemplates\":{\"team\":{\"templateVersionId\":\"draft\"}}}"));
        fails("INVALID_TEMPLATE", () -> renderer.validate(root));
    }

    @Test
    void acceptsZipDirectoryEntriesAndRejectsDamagedOrMislabeledMainParts() throws Exception {
        var bytes = document("归档清单");
        var withDirectory = repack(bytes, null, UnaryOperator.identity());
        renderer.validateFile(withDirectory);
        var damaged = repack(bytes, "word/document.xml", xml -> "<document");
        var incorrectType = repack(bytes, "[Content_Types].xml",
                xml -> xml.replace("wordprocessingml.document.main+xml", "wordprocessingml.styles+xml"));
        for (byte[] invalid : List.of(new byte[0], damaged, incorrectType)) {
            fails("UNSUPPORTED_FILE_TYPE", () -> renderer.validateFile(invalid));
        }
    }

    @Test
    void acceptsOnePackageThumbnailAlongsideDocumentPictures() throws Exception {
        byte[] thumbnail = noisePicture(16, 42);
        try (var doc = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 10; i++) {
                doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(noisePicture(16, i)),
                        org.apache.poi.common.usermodel.PictureType.PNG, "目录图片" + i + ".png",
                        Units.toEMU(16), Units.toEMU(16));
            }
            addThumbnail(doc, "thumbnail.png", thumbnail);
            doc.write(output);
            renderer.validateFile(output.toByteArray());

            addThumbnail(doc, "thumbnail-extra.png", thumbnail);
            output.reset();
            doc.write(output);
            byte[] withExtraThumbnail = output.toByteArray();
            fails("INVALID_TEMPLATE", () -> renderer.validateFile(withExtraThumbnail));
        }
    }

    private static void addThumbnail(XWPFDocument doc, String name, byte[] thumbnail) throws Exception {
        var pack = doc.getPackage();
        var partName = PackagingURIHelper.createPartName("/docProps/" + name);
        var part = pack.createPart(partName, "image/png");
        try (var output = part.getOutputStream()) {
            output.write(thumbnail);
        }
        pack.addRelationship(partName, TargetMode.INTERNAL, PackageRelationshipTypes.THUMBNAIL);
    }

    @Test
    void acceptsPageCountsAndTheSupportedTableOfContentsSwitches() throws Exception {
        String[] instructions = {" Page ", "NUMPAGES", "TOC", "toc \\h\t\\Z \\o  \"1-3\" \\u"};
        for (boolean simpleField : new boolean[]{true, false}) {
            byte[] bytes = documentWithFields(simpleField, instructions);
            assertThatCode(() -> renderer.validateFile(bytes)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsUnknownFieldNamesAndInvalidTableOfContentsSwitches() throws Exception {
        for (String instruction : List.of("PAGEX", "PAGE \\h", "TOC \\o \"0-2\"", "TOC \\x")) {
            for (boolean simpleField : new boolean[]{true, false}) {
                byte[] bytes = documentWithFields(simpleField, instruction);
                var failure = assertThrows(RenderFailure.class, () -> renderer.validateFile(bytes));
                assertThat(failure.code()).isEqualTo("INVALID_TEMPLATE");
            }
        }
    }

    private static byte[] documentWithFields(boolean simpleField, String... instructions) throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var paragraph = doc.createParagraph().getCTP();
            for (String instruction : instructions) {
                if (simpleField) {
                    paragraph.addNewFldSimple().setInstr(instruction);
                } else {
                    paragraph.addNewR().addNewInstrText().setStringValue(instruction);
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] repack(byte[] original, String part, UnaryOperator<String> rewrite) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(original)); var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/"));
            zip.closeEntry();
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                var data = input.readAllBytes();
                if (entry.getName().equals(part)) {
                    data = rewrite.apply(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(data);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void sharedResourceBudgetAllowsExactLimitsAndRejectsFirstExcess() {
        var budget = new RenderBudget();
        for (int i = 0; i < 4; i++) {
            budget.loaded(5 * MIB);
            budget.markup(12L * MIB, "rows");
            budget.intermediate(8 * MIB);
        }
        for (int i = 0; i < 10; i++) budget.picture(2_400_000, "logo");
        fails("INVALID_TEMPLATE", () -> budget.loaded(1));
        fails("INVALID_RENDER_DATA", () -> budget.markup(1, "rows"));
        fails("INVALID_RENDER_DATA", () -> budget.intermediate(1));
        fails("INVALID_RENDER_DATA", () -> budget.picture(1, "logo"));
        var pixels = new RenderBudget();
        pixels.picture(12_000_000, "first");
        pixels.picture(12_000_000, "second");
        fails("INVALID_RENDER_DATA", () -> pixels.picture(1, "third"));
    }

    @Test
    void staticPicturesRemainAvailableAndDynamicPicturesRequireExplicitTags() throws Exception {
        var schema = JSON.readTree("{\"type\":\"object\",\"properties\":{\"image\":{\"type\":\"object\"}}}");
        byte[] original = noisePicture(64, 91);
        try (var doc = new XWPFDocument()) {
            var run = doc.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(original), org.apache.poi.common.usermodel.PictureType.PNG,
                    "部门标识.png", Units.toEMU(64), Units.toEMU(64));
            var out = new ByteArrayOutputStream();
            doc.write(out);
            var staticImage = new Template(out.toByteArray(), schema, null);
            var rendered = renderer.render(staticImage, JSON.createObjectNode(), id -> null);
            try (var result = new XWPFDocument(new ByteArrayInputStream(rendered))) {
                assertThat(result.getAllPictures().getFirst().getData()).isEqualTo(original);
            }
            try (var cursor = run.getCTR().getDrawingArray(0).getInlineArray(0).getDocPr().newCursor()) {
                cursor.setAttributeText(new QName("title"), "{{image}}");
            }
            out.reset();
            doc.write(out);
            var implicitImage = new Template(out.toByteArray(), schema, null);
            fails("INVALID_TEMPLATE", () -> renderer.validate(implicitImage));
        }
    }

    @Test
    void rejectsImagesWithAnExcessiveIntrinsicPixelCountEvenAtSmallDisplaySize() throws Exception {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(4000, 3001, BufferedImage.TYPE_BYTE_BINARY);
        ImageIO.write(image, "PNG", output);
        var input = JSON.createObjectNode();
        var model = input.putObject("image").put("width", 50).put("height", 50).put("mediaType", "image/png");
        model.put("base64", Base64.getEncoder().encodeToString(output.toByteArray()));
        var root = new Template(document("{{@image}}"),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"image\":{\"type\":\"object\"}}}"), null);
        fails("INVALID_RENDER_DATA", () -> renderer.render(root, input, id -> null));
    }

    @Test
    void rejectsAnOversizedResultEvenWhenTheSourceAndInputSeparatelyFit() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var doc = new XWPFDocument()) {
            for (int i = 0; i < 6; i++) {
                var png = noisePicture(512, i);
                doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(png),
                        org.apache.poi.common.usermodel.PictureType.PNG, "归档图片" + i + ".png", Units.toEMU(128),
                        Units.toEMU(128));
            }
            doc.createParagraph().createRun().setText("{{@image}}");
            doc.write(out);
        }
        assertThat(out.size()).isBetween(4 * MIB, 5 * MIB);
        var schema = JSON.readTree("{\"type\":\"object\",\"properties\":{\"image\":{\"type\":\"object\"}}}");
        var root = new Template(out.toByteArray(), schema, null);
        var input = JSON.createObjectNode();
        input.putObject("image")
                .put("mediaType", "image/png")
                .put("width", 128)
                .put("height", 128)
                .put("base64", Base64.getEncoder().encodeToString(noisePicture(512, 6)));
        renderer.validate(root);
        renderer.validateInput(root, input);
        fails("FILE_TOO_LARGE", () -> renderer.render(root, input, id -> null));
    }

    private static byte[] noisePicture(int size, long seed) throws Exception {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
        new Random(seed).nextBytes(((DataBufferByte) image.getRaster().getDataBuffer()).getData());
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    @Test
    void shrinkingChartTrimsWorkbookDataRowsAndItsNamedTable() throws Exception {
        Path folder = Path.of(getClass().getResource("/document-generation-samples/04-media").toURI());
        var meta = JSON.readTree(folder.resolve("template.json").toFile());
        var input = (ObjectNode) JSON.readTree(folder.resolve("png.json").toFile());
        var source = (ObjectNode) input.get("activityBar");
        source.putArray("categories").add("十一月");
        for (var series : source.get("series")) ((ObjectNode) series).putArray("values").add(17);
        var out = new ByteArrayOutputStream();
        try (var sourceFile = Files.newInputStream(folder.resolve("template.docx"));
             var doc = new XWPFDocument(sourceFile)) {
            var bar = doc.getCharts()
                    .stream()
                    .filter(c -> c.getChartSeries().getFirst().getSeriesCount() == 2)
                    .findFirst()
                    .orElseThrow();
            var book = bar.getWorkbook();
            var sheet = book.getSheetAt(0);
            var table = sheet.createTable(new AreaReference("A1:C3", SpreadsheetVersion.EXCEL2007));
            table.setName("QuarterlyData");
            table.setDisplayName("QuarterlyData");
            bar.saveWorkbook(book);
            doc.write(out);
        }
        var root = new Template(out.toByteArray(), meta.get("inputSchema"), meta.get("renderConfig"));
        var bytes = renderer.render(root, input, id -> null);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var bar = doc.getCharts()
                    .stream()
                    .filter(c -> c.getChartSeries().getFirst().getSeriesCount() == 2)
                    .findFirst()
                    .orElseThrow();
            var sheet = bar.getWorkbook().getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getTables().getFirst().getEndRowIndex()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("十一月");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(17);
        }
    }
}
