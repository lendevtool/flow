package me.lendev.flow.docgen.render;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.data.DocxRenderData;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.deepoove.poi.policy.DocxRenderPolicy;
import com.deepoove.poi.policy.reference.AbstractTemplateRenderPolicy;
import com.deepoove.poi.render.RenderContext;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.ChartTemplate;
import com.deepoove.poi.template.run.RunTemplate;
import com.deepoove.poi.xwpf.NiceXWPFDocument;
import org.apache.commons.io.IOUtils;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFPieChartData;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class WordPolicies {
    private WordPolicies() {
    }

    static final class ChartPolicy extends AbstractTemplateRenderPolicy<ChartTemplate, Object> {
        private final ChartTypes type;

        ChartPolicy(String type) {
            this.type = ChartTypes.valueOf(type);
        }

        static boolean validType(ChartTypes type, XDDFChartData plot) {
            return switch (type) {
                case BAR -> plot instanceof XDDFBarChartData;
                case LINE -> plot instanceof XDDFLineChartData;
                case PIE -> plot instanceof XDDFPieChartData;
                default -> false;
            };
        }

        private static void check(boolean ok, ChartTemplate element) {
            if (!ok)
                throw RenderFailure.data(element.getTagName(), "图表类型、系列数量或类目与样板不匹配");
        }

        @Override
        public void doRender(ChartTemplate element, Object source, XWPFTemplate template)
                throws IOException, InvalidFormatException {
            JsonNode data = InputSchema.JSON.valueToTree(source);
            var chart = element.getChart();
            var plot = chart.getChartSeries().getFirst();
            var allSeries = data.path("series");
            var labels = data.path("categories");
            check(data.path("title").isString() && allSeries.isArray() && labels.isArray() && labels.size() > 0
                            && labels.size() <= 100,
                    element);
            String[] categories = new String[labels.size()];
            for (int i = 0; i < categories.length; i++) {
                check(labels.get(i).isString(), element);
                categories[i] = labels.get(i).asString();
            }
            check(chart.getChartSeries().size() == 1 && validType(type, plot), element);
            check(plot.getSeriesCount() == allSeries.size() && allSeries.size() <= 10, element);
            if (type == ChartTypes.PIE)
                check(allSeries.size() == 1, element);
            var sheet = chart.getWorkbook().getSheetAt(0);
            for (int i = sheet.getLastRowNum(); i > categories.length; i--) {
                var row = sheet.getRow(i);
                if (row != null)
                    sheet.removeRow(row);
            }
            var categoryData = XDDFDataSourcesFactory.fromArray(
                    categories, chart.formatRange(new CellRangeAddress(1, categories.length, 0, 0)), 0);
            for (int index = 0; index < allSeries.size(); index++) {
                var row = allSeries.get(index);
                var points = row.path("values");
                check(row.path("name").isString() && points.isArray() && points.size() == categories.length, element);
                Number[] values = new Number[categories.length];
                for (int i = 0; i < values.length; i++) {
                    check(points.get(i).isNumber() && Double.isFinite(points.get(i).asDouble()), element);
                    values[i] = points.get(i).numberValue();
                }
                int column = index + 1;
                var valueData = XDDFDataSourcesFactory.fromArray(
                        values, chart.formatRange(new CellRangeAddress(1, categories.length, column, column)), column);
                var series = plot.getSeries(index);
                series.replaceData(categoryData, valueData);
                String name = row.get("name").asString();
                series.setTitle(name, chart.setSheetTitle(name, column));
            }
            chart.plot(plot);
            for (var table : sheet.getTables()) {
                table.setArea(new AreaReference(
                        new CellRangeAddress(0, categories.length, 0, allSeries.size()).formatAsString(),
                        SpreadsheetVersion.EXCEL2007));
            }
            var title = data.get("title").asString();
            chart.setTitleText(title);
            chart.setTitleOverlay(false);
            var properties = element.getRun().getCTR().getDrawingArray(0).getInlineArray(0).getDocPr();
            properties.setDescr(title);
        }
    }

    static final class GroupLoop extends LoopRowTableRenderPolicy {
        private final String key;
        private final int column;

        GroupLoop(String key, int column) {
            this.key = key;
            this.column = column;
        }

        @Override
        public void render(ElementTemplate element, Object data, XWPFTemplate template) {
            var run = ((RunTemplate) element).getRun();
            var tagCell = (XWPFTableCell) ((XWPFParagraph) run.getParent()).getBody();
            var tagRow = tagCell.getTableRow();
            var table = tagRow.getTable();
            int start = table.getRows().indexOf(tagRow) + 1;
            super.render(element, data, template);

            List<?> rows = data instanceof List<?> list ? list : List.of();
            for (int i = 0; i < rows.size(); i++) {
                Object group = DocxRenderer.lookup(rows.get(i), key);
                boolean joinPrevious = i > 0 && sameGroup(group, DocxRenderer.lookup(rows.get(i - 1), key));
                boolean joinNext = i + 1 < rows.size() && sameGroup(group, DocxRenderer.lookup(rows.get(i + 1), key));
                if (joinPrevious || joinNext) {
                    mergeCell(table.getRow(start + i).getCell(column), joinPrevious);
                }
            }
        }

        private static void mergeCell(XWPFTableCell cell, boolean joinPrevious) {
            var properties = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            var merge = properties.isSetVMerge() ? properties.getVMerge() : properties.addNewVMerge();
            merge.setVal(joinPrevious ? STMerge.CONTINUE : STMerge.RESTART);
            if (joinPrevious) {
                for (var paragraph : cell.getParagraphs()) {
                    while (!paragraph.getRuns().isEmpty()) paragraph.removeRun(0);
                }
            }
        }

        private boolean sameGroup(Object left, Object right) {
            return left != null && !left.toString().isBlank() && Objects.equals(left, right);
        }
    }

    static final class ChildDocs extends DocxRenderData {
        private final List<byte[]> documents;

        ChildDocs(List<byte[]> documents) {
            super(new byte[0], null);
            this.documents = List.copyOf(documents);
        }
    }

    static final class ChildPolicy extends DocxRenderPolicy {
        @Override
        public void doRender(RenderContext<DocxRenderData> context) throws Exception {
            List<byte[]> bytes = ((ChildDocs) context.getData()).documents;
            if (bytes.isEmpty()) {
                clearPlaceholder(context, true);
                return;
            }
            try (var children = new OpenedChildren()) {
                children.open(bytes);
                var merged = context.getXWPFDocument().merge(children.documents, context.getRun());
                context.getTemplate().reload(merged);
            }
        }
    }

    /**
     * 即使子文档打开或合并失败，也关闭已经打开的文档。
     */
    private static final class OpenedChildren implements Closeable {
        private final List<NiceXWPFDocument> documents = new ArrayList<>();

        private void open(List<byte[]> sources) throws IOException {
            for (byte[] source : sources) {
                documents.add(new NiceXWPFDocument(new ByteArrayInputStream(source)));
            }
        }

        @Override
        public void close() throws IOException {
            IOUtils.close(documents.toArray(NiceXWPFDocument[]::new));
        }
    }
}
