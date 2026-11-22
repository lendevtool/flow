package me.lendev.flow.calendar;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * 生成交易日历导入模板，并校验工作簿的列结构和单元格数据。
 */
@Component
public class TradingCalendarExcel {

    private static final List<String> SINGLE_COLUMNS = List.of("日期", "是否交易日", "节假日", "备注");
    private static final String MULTI_MODE = "MULTI";
    private static final List<String> MULTI_COLUMNS = List.of("市场代码", "日期", "是否交易日", "节假日", "备注");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern COMPACT_DATE = Pattern.compile("\\d{8}");
    private static final Pattern MARKET_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final String DATE_ERROR = "日期无效，请使用 Excel 日期、yyyy-MM-dd 或 yyyyMMdd";
    private static final String FILE_ERROR = "无法读取文件，请上传有效的 .xlsx 工作簿";
    private static final String SIZE_ERROR = "工作簿超出导入大小限制";
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_UNZIPPED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 128;
    private static final int MAX_DATA_ROWS = 10_000;
    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 9999;
    private static final int MARKET_CODE_LENGTH = 64;
    private static final int HOLIDAY_LENGTH = 100;
    private static final int REMARK_LENGTH = 500;

    /**
     * 生成指定模式的空白 Excel 模板，填写说明位于独立工作表。
     *
     * @param mode 导入模式，取值为 SINGLE 或 MULTI
     * @return .xlsx 文件内容
     */
    public byte[] template(String mode) {
        List<String> columns = columns(mode);
        try (var workbook = new XSSFWorkbook(); var buffer = new ByteArrayOutputStream()) {
            Sheet data = workbook.createSheet("交易日历");
            data.createFreezePane(0, 1);
            var title = data.createRow(0);
            title.setHeightInPoints(22);
            var style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int i = 0; i < columns.size(); i++) {
                var cell = title.createCell(i);
                cell.setCellValue(columns.get(i));
                cell.setCellStyle(style);
                data.setColumnWidth(i, (i == columns.size() - 1 ? 40 : 20) * 256);
            }
            if (MULTI_MODE.equals(mode)) {
                var textStyle = workbook.createCellStyle();
                textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
                data.setDefaultColumnStyle(0, textStyle);
            }
            writeInstructions(workbook, mode);
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("无法生成交易日历模板", ex);
        }
    }

    private static void writeInstructions(XSSFWorkbook workbook, String mode) {
        Sheet instructions = workbook.createSheet("填写说明");
        instructions.setColumnWidth(0, 85 * 256);
        List<String> lines = List.of(
                "请从「交易日历」工作表的第 2 行开始填写，保留首行列名。",
                MULTI_MODE.equals(mode) ? "市场代码：请填写系统中已配置的市场代码。" : "市场在导入时选择，无需在模板中填写。",
                "日期：填写 Excel 日期、yyyy-MM-dd 或 yyyyMMdd。",
                "是否交易日：填写 1（交易日）或 0（休市）。",
                "节假日和备注可留空，分别最多填写 100 和 500 个字符。",
                "同一市场和日期只能出现一次，不支持公式。每个文件最多导入 10,000 条记录。");
        for (int i = 0; i < lines.size(); i++) {
            instructions.createRow(i).createCell(0).setCellValue(lines.get(i));
        }
    }

    /**
     * 读取工作簿的首张工作表，保留 Excel 行号；不关闭调用方提供的输入流。 工作簿或任意数据行校验失败时，调用方不得导入返回的记录。
     *
     * @param input      工作簿输入流
     * @param mode       导入模式，取值为 SINGLE 或 MULTI
     * @param marketCode SINGLE 模式的市场代码；MULTI 模式从工作表读取
     * @return 解析出的有效记录和错误，文件级错误使用行号 0
     */
    public Parsed parse(InputStream input, String mode, String marketCode) {
        List<String> columns = columns(mode);
        try {
            byte[] contents = input.readNBytes(MAX_FILE_BYTES + 1);
            if (contents.length > MAX_FILE_BYTES || exceedsZipLimits(contents)) {
                return failed(0, SIZE_ERROR);
            }
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(contents))) {
                if (workbook.isMacroEnabled() || workbook.getNumberOfSheets() == 0) {
                    return failed(0, FILE_ERROR);
                }
                return parseSheet(workbook.getSheetAt(0), columns, marketCode, workbook.isDate1904());
            }
        } catch (IOException | RuntimeException ex) {
            return failed(0, FILE_ERROR);
        }
    }

    // 限制总解压量和条目数，避免多个压缩条目绕过单条目的大小限制。
    private static boolean exceedsZipLimits(byte[] contents) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(contents))) {
            byte[] buffer = new byte[8192];
            int entries = 0;
            int expanded = 0;
            while (zip.getNextEntry() != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    return true;
                }
                for (int read; (read = zip.read(buffer)) != -1; ) {
                    expanded += read;
                    if (expanded > MAX_UNZIPPED_BYTES) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Parsed parseSheet(Sheet sheet, List<String> columns, String marketCode, boolean date1904) {
        if (!matchesHeader(sheet.getRow(0), columns)) {
            return failed(1, "表头不匹配，请使用对应的导入模板");
        }
        List<Row> rows = new ArrayList<>();
        List<Issue> errors = new ArrayList<>();
        Set<RowKey> seen = new HashSet<>();
        boolean multi = columns.size() == MULTI_COLUMNS.size();
        for (var cells : sheet) {
            if (cells.getRowNum() == 0 || isEmpty(cells)) {
                continue;
            }
            if (rows.size() + errors.size() >= MAX_DATA_ROWS) {
                return failed(cells.getRowNum() + 1, "每个文件最多导入 10,000 条记录");
            }
            try {
                verifyColumns(cells, columns.size());
                Row row = readRow(cells, multi, marketCode, date1904);
                if (!seen.add(new RowKey(row.marketCode(), row.date()))) {
                    throw new InvalidRow("同一市场和日期存在重复记录");
                }
                rows.add(row);
            } catch (InvalidRow ex) {
                errors.add(new Issue(cells.getRowNum() + 1, ex.getMessage()));
            }
        }
        return new Parsed(List.copyOf(rows), List.copyOf(errors));
    }

    private static boolean matchesHeader(org.apache.poi.ss.usermodel.Row row, List<String> columns) {
        if (row == null) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = row.getCell(i);
            if (cell == null || cell.getCellType() != CellType.STRING
                    || !columns.get(i).equals(cell.getStringCellValue().strip())) {
                return false;
            }
        }
        for (Cell cell : row) {
            if (cell.getColumnIndex() >= columns.size() && !blank(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmpty(org.apache.poi.ss.usermodel.Row row) {
        for (Cell cell : row) {
            if (!blank(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(Cell cell) {
        return cell == null || cell.getCellType() == CellType.BLANK
                || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
    }

    private static void verifyColumns(org.apache.poi.ss.usermodel.Row row, int columnCount) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA) {
                throw new InvalidRow("不支持公式，请粘贴为值");
            }
            if (cell.getColumnIndex() >= columnCount && !blank(cell)) {
                throw new InvalidRow("模板列之外存在数据");
            }
        }
    }

    private static Row readRow(org.apache.poi.ss.usermodel.Row cells, boolean multi, String marketCode, boolean date1904) {
        int offset = multi ? 1 : 0;
        String market = multi ? optionalText(cells.getCell(0), "市场代码", MARKET_CODE_LENGTH) : marketCode;
        if (market == null || market.isBlank()) {
            throw new InvalidRow("市场代码不能为空");
        }
        String normalizedMarket = market.strip().toUpperCase(Locale.ROOT);
        if (!MARKET_CODE.matcher(normalizedMarket).matches()) {
            throw new InvalidRow("市场代码格式不正确");
        }
        LocalDate date = readDate(cells.getCell(offset), date1904);
        boolean isTradingDay = readStatus(cells.getCell(offset + 1));
        String holidayName = optionalText(cells.getCell(offset + 2), "节假日", HOLIDAY_LENGTH);
        String remark = optionalText(cells.getCell(offset + 3), "备注", REMARK_LENGTH);
        return new Row(cells.getRowNum() + 1, normalizedMarket, date, isTradingDay, holidayName, remark);
    }

    private static LocalDate readDate(Cell cell, boolean date1904) {
        if (cell == null) {
            throw new InvalidRow(DATE_ERROR);
        }
        LocalDate date;
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value >= 10_000_000 && value <= 99_999_999 && value == Math.rint(value)) {
                date = parseDateText(Integer.toString((int) value));
            } else if (value >= 0 && value <= 2_958_465 && value == Math.rint(value)
                    && (date1904 || value != 60) && DateUtil.isCellDateFormatted(cell)) {
                date = cell.getLocalDateTimeCellValue().toLocalDate();
            } else {
                throw new InvalidRow(DATE_ERROR);
            }
        } else if (cell.getCellType() == CellType.STRING) {
            date = parseDateText(cell.getStringCellValue().strip());
        } else {
            throw new InvalidRow(DATE_ERROR);
        }
        if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
            throw new InvalidRow(DATE_ERROR);
        }
        return date;
    }

    private static LocalDate parseDateText(String value) {
        try {
            if (ISO_DATE.matcher(value).matches()) {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            }
            if (COMPACT_DATE.matcher(value).matches()) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            }
        } catch (DateTimeParseException ex) {
            throw new InvalidRow(DATE_ERROR);
        }
        throw new InvalidRow(DATE_ERROR);
    }

    private static boolean readStatus(Cell cell) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value == 1 || value == 0) {
                return value == 1;
            }
        } else if (cell != null && cell.getCellType() == CellType.STRING) {
            String value = cell.getStringCellValue().strip();
            if ("1".equals(value) || "0".equals(value)) {
                return "1".equals(value);
            }
        }
        throw new InvalidRow("是否交易日必须填写 1 或 0");
    }

    private static String optionalText(Cell cell, String column, int maxLength) {
        if (blank(cell)) {
            return null;
        }
        if (cell.getCellType() != CellType.STRING) {
            throw new InvalidRow(column + "必须填写文本");
        }
        String value = cell.getStringCellValue().strip();
        if (value.length() > maxLength) {
            throw new InvalidRow(column + "不能超过 " + maxLength + " 个字符");
        }
        return value;
    }

    private static List<String> columns(String mode) {
        return switch (mode) {
            case "SINGLE" -> SINGLE_COLUMNS;
            case MULTI_MODE -> MULTI_COLUMNS;
            case null, default -> throw new IllegalArgumentException("不支持的交易日历导入模式");
        };
    }

    private static Parsed failed(int row, String message) {
        return new Parsed(List.of(), List.of(new Issue(row, message)));
    }

    /**
     * 工作簿解析结果；任意错误均阻止整份文件导入。
     */
    public record Parsed(List<Row> rows, List<Issue> errors) {

    }

    /**
     * 一条通过单元格校验的交易日历记录。
     */
    public record Row(int row, String marketCode, LocalDate date, boolean isTradingDay, String holidayName,
                      String remark) {

    }

    /**
     * 安全的校验消息及对应的 Excel 行号。
     */
    public record Issue(int row, String message) {

    }

    private record RowKey(String marketCode, LocalDate date) {

    }

    private static class InvalidRow extends RuntimeException {

        private InvalidRow(String message) {
            super(message);
        }
    }
}
