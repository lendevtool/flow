package me.lendev.flow.calendar;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIterable;

class TradingCalendarExcelTest {

    private final TradingCalendarExcel excel = new TradingCalendarExcel();

    @Test
    void templatesHaveModeSpecificHeadersAndNoDataRows() throws IOException {
        assertTemplate("SINGLE", List.of("日期", "是否交易日", "节假日", "备注"));
        assertTemplate("MULTI", List.of("市场代码", "日期", "是否交易日", "节假日", "备注"));
    }

    @Test
    void parsesSingleMarketDatesAsExcelDatesIsoTextAndCompactValues() throws IOException {
        try (var workbook = template("SINGLE")) {
            var sheet = workbook.getSheetAt(0);
            var first = sheet.createRow(1);
            var date = first.createCell(0);
            date.setCellValue(LocalDate.of(2026, 1, 1));
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(dateStyle);
            first.createCell(1).setCellValue(0);
            first.createCell(2).setCellValue(" 元旦 ");
            first.createCell(3).setCellValue(" 休市 ");
            sheet.createRow(2); // Blank rows do not affect Excel row numbers.
            var third = sheet.createRow(3);
            third.createCell(0).setCellValue("2026-01-02");
            third.createCell(1).setCellValue(" 1 ");
            var fourth = sheet.createRow(4);
            fourth.createCell(0).setCellValue(20260103);
            fourth.createCell(1).setCellValue("0");
            var fifth = sheet.createRow(5);
            fifth.createCell(0).setCellValue("20260104");
            fifth.createCell(1).setCellValue(1);

            var result = excel.parse(bytes(workbook), "SINGLE", " sse ");

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).containsExactly(
                    new TradingCalendarExcel.Row(2, "SSE", LocalDate.of(2026, 1, 1), false, "元旦", "休市"),
                    new TradingCalendarExcel.Row(4, "SSE", LocalDate.of(2026, 1, 2), true, null, null),
                    new TradingCalendarExcel.Row(5, "SSE", LocalDate.of(2026, 1, 3), false, null, null),
                    new TradingCalendarExcel.Row(6, "SSE", LocalDate.of(2026, 1, 4), true, null, null));
        }
    }

    @Test
    void rejectsDuplicatesAcrossAlternateMarketAndDateSpellings() throws IOException {
        try (var workbook = template("MULTI")) {
            var sheet = workbook.getSheetAt(0);
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue(" sse ");
            first.createCell(1).setCellValue("2026-02-03");
            first.createCell(2).setCellValue(1);
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue("SSE");
            second.createCell(1).setCellValue("20260203");
            second.createCell(2).setCellValue(0);
            var third = sheet.createRow(3);
            third.createCell(0).setCellValue("SZSE");
            third.createCell(1).setCellValue("2026-02-03");
            third.createCell(2).setCellValue(0);

            var result = excel.parse(bytes(workbook), "MULTI", null);

            assertThat(result.errors()).singleElement().satisfies(error -> {
                assertThat(error.row()).isEqualTo(3);
                assertThat(error.message()).contains("重复");
            });
            assertThat(result.rows()).extracting(TradingCalendarExcel.Row::marketCode).containsExactly("SSE", "SZSE");
        }
    }

    @Test
    void reportsTheRowsWithInvalidValuesFormulasAndUnexpectedColumns() throws IOException {
        try (var workbook = template("SINGLE")) {
            var sheet = workbook.getSheetAt(0);
            var invalidDate = sheet.createRow(1);
            invalidDate.createCell(0).setCellValue("2026-02-30");
            invalidDate.createCell(1).setCellValue(1);
            var invalidStatus = sheet.createRow(2);
            invalidStatus.createCell(0).setCellValue("2026-01-01");
            invalidStatus.createCell(1).setCellValue(true);
            var formula = sheet.createRow(3);
            formula.createCell(0).setCellValue("2026-01-02");
            formula.createCell(1).setCellFormula("1");
            var longHoliday = sheet.createRow(4);
            longHoliday.createCell(0).setCellValue("2026-01-03");
            longHoliday.createCell(1).setCellValue(1);
            longHoliday.createCell(2).setCellValue("节".repeat(101));
            var longRemark = sheet.createRow(5);
            longRemark.createCell(0).setCellValue("2026-01-04");
            longRemark.createCell(1).setCellValue(0);
            longRemark.createCell(3).setCellValue("备注".repeat(251));
            var extra = sheet.createRow(6);
            extra.createCell(4).setCellValue("填到了第 5 列");

            var result = excel.parse(bytes(workbook), "SINGLE", "SSE");

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).extracting(TradingCalendarExcel.Issue::row).containsExactly(2, 3, 4, 5, 6, 7);
            assertThat(result.errors()).extracting(TradingCalendarExcel.Issue::message).anyMatch(s -> s.contains("公式"));
        }
    }

    @Test
    void requiresTheHeaderForTheSelectedModeOnTheFirstSheet() throws IOException {
        try (var workbook = template("SINGLE")) {
            workbook.setSheetOrder("填写说明", 0);

            var result = excel.parse(bytes(workbook), "SINGLE", "SSE");

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.row()).isEqualTo(1));
        }
    }

    @Test
    void rejectsMissingHeadersAndExtraHeaderCells() throws IOException {
        try (var workbook = template("SINGLE")) {
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            header.createCell(4).setCellValue("Extra");
            assertThat(excel.parse(bytes(workbook), "SINGLE", "CN").errors())
                    .extracting(TradingCalendarExcel.Issue::row).containsExactly(1);
            sheet.removeRow(header);
            assertThat(excel.parse(bytes(workbook), "SINGLE", "CN").errors())
                    .extracting(TradingCalendarExcel.Issue::row).containsExactly(1);
        }
    }

    @Test
    void reportsMissingAndMistypedCellsWithoutDiscardingValidRows() throws IOException {
        try (var workbook = template("MULTI")) {
            var sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(2).setCellValue(1);
            for (int index = 2; index <= 9; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue(index == 2 ? "bad market!" : "CN");
                row.createCell(2).setCellValue(1);
                switch (index) {
                    case 3 -> {
                        // 故意不创建日期单元格，覆盖日期缺失的情况。
                    }
                    case 4 -> row.createCell(1).setCellValue(true);
                    case 5 -> row.createCell(1).setCellValue(42.5);
                    case 6 -> row.createCell(1).setCellValue("0999-01-01");
                    case 7 -> row.createCell(1).setCellValue("tomorrow");
                    default -> row.createCell(1).setCellValue("2026-01-01");
                }
                if (index == 8) {
                    row.createCell(3).setCellValue(42);
                }
            }
            var parsed = excel.parse(bytes(workbook), "MULTI", null);
            assertThat(parsed.errors()).extracting(TradingCalendarExcel.Issue::row)
                    .containsExactly(2, 3, 4, 5, 6, 7, 8, 9);
            assertThat(parsed.rows()).extracting(TradingCalendarExcel.Row::row).containsExactly(10);
        }
    }

    @Test
    void rejectsHolidayNamesTooLongForTheCalendarColumnBeforeImport() throws IOException {
        try (var workbook = template("SINGLE")) {
            var row = workbook.getSheetAt(0).createRow(1);
            row.createCell(0).setCellValue("2026-01-01");
            row.createCell(1).setCellValue(0);
            row.createCell(2).setCellValue("🎉".repeat(51));

            var result = excel.parse(bytes(workbook), "SINGLE", "CN");

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.row()).isEqualTo(2));
        }
    }

    @Test
    void rejectsOversizedMarketCodesWithoutEchoingTheCellContents() throws IOException {
        try (var workbook = template("MULTI")) {
            var row = workbook.getSheetAt(0).createRow(1);
            String oversized = "PRIVATE".repeat(200);
            row.createCell(0).setCellValue(oversized);
            row.createCell(1).setCellValue("2026-01-01");
            row.createCell(2).setCellValue(0);

            var result = excel.parse(bytes(workbook), "MULTI", null);

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).singleElement().satisfies(issue -> {
                assertThat(issue.row()).isEqualTo(2);
                assertThat(issue.message()).contains("市场代码").doesNotContain(oversized);
            });
        }
    }

    @Test
    void returnsASafeFileIssueForUnreadableWorkbooks() {
        var input = new ByteArrayInputStream("private raw workbook data".getBytes(StandardCharsets.UTF_8));

        var result = excel.parse(input, "SINGLE", "SSE");

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.row()).isZero();
            assertThat(error.message()).contains(".xlsx").doesNotContain("private raw workbook data");
        });
    }

    @Test
    void rejectsFilesThatExpandBeyondTheWorkbookLimit() throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("xl/oversized.xml"));
            var block = new byte[1024 * 1024];
            for (int i = 0; i < 33; i++) {
                zip.write(block);
            }
        }

        var result = excel.parse(new ByteArrayInputStream(buffer.toByteArray()), "SINGLE", "SSE");

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.row()).isZero();
            assertThat(error.message()).contains("大小");
        });
    }

    private void assertTemplate(String mode, List<String> columns) throws IOException {
        try (var workbook = template(mode)) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("交易日历");
            assertThat(sheet.getPhysicalNumberOfRows()).isOne();
            assertThatIterable(sheet.getRow(0)).extracting(Cell::getStringCellValue).containsExactlyElementsOf(columns);
            assertThat(workbook.getSheet("填写说明")).isNotNull();
        }
    }

    private XSSFWorkbook template(String mode) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(excel.template(mode)));
    }

    private ByteArrayInputStream bytes(XSSFWorkbook workbook) throws IOException {
        var buffer = new ByteArrayOutputStream();
        workbook.write(buffer);
        return new ByteArrayInputStream(buffer.toByteArray());
    }
}
