package me.lendev.flow.calendar;

import static me.lendev.flow.calendar.TradingCalendarModels.*;
import static me.lendev.flow.common.auth.PermissionCode.*;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.ManagedReadInvocation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供交易日历查询、初始化、手工设置和 Excel 导入的 HTTP 接口。
 */
@RestController
@RequestMapping(TradingCalendarController.ROOT_PATH)
@RequiredArgsConstructor
@Tag(name = "交易日历")
public class TradingCalendarController {

    static final String ROOT_PATH = "/trading-calendar";
    static final String MARKETS_PATH = "/markets";
    static final String YEAR_PATH = "/year";
    static final String STATUS_PATH = "/status";
    static final String OFFSET_PATH = "/offset";

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TradingCalendarService service;
    private final TradingCalendarExcel excel;
    private final OpenTradingCalendarService appReads;

    /**
     * 查询日历查询与维护可使用的启用市场。
     *
     * @return 市场编码、名称、时区和图标配置
     */
    @GetMapping(MARKETS_PATH)
    public R<List<Market>> markets() {
        permitRead(MARKETS_PATH);
        return R.ok(service.markets());
    }

    /**
     * 查询一个自然年度的已配置日期，按市场及日期排列。
     *
     * @param year        自然年，范围为 1000 至 9999
     * @param marketCodes 逗号分隔的启用市场编码
     * @return 已覆盖的日期记录，缺少覆盖的日期省略
     */
    @GetMapping(YEAR_PATH)
    public R<List<Day>> year(@RequestParam int year, @RequestParam String marketCodes) {
        var codes = List.of(marketCodes.split(",", -1));
        return read(YEAR_PATH, () -> service.year(year, codes), () -> appReads.readYear(year, codes));
    }

    /**
     * 读取指定市场在单个日期的日历配置。
     *
     * @param marketCode 启用的市场编码
     * @param date       采用 ISO 日期格式的自然日
     * @return 交易状态、假日名称及记录来源
     */
    @GetMapping(STATUS_PATH)
    public R<Day> status(@RequestParam String marketCode,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return read(STATUS_PATH, () -> service.status(marketCode, date), () -> appReads.readStatus(marketCode, date));
    }

    /**
     * 按日历配置向未来或过去计数，遇到未配置的日期时终止。
     *
     * @param marketCode 启用的市场编码
     * @param date       已覆盖的基准日期
     * @param n          交易日偏移量，零要求基准日开市
     * @return 命中的交易日
     */
    @GetMapping(OFFSET_PATH)
    public R<Offset> offset(@RequestParam String marketCode,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam int n) {
        return read(OFFSET_PATH, () -> service.offset(marketCode, date, n), () -> appReads.readOffset(marketCode, date, n));
    }

    /**
     * 预览按年度初始化交易日历的影响。
     *
     * @param request 初始化的年份和市场集合
     * @return 新增、变化和未变化数量，以及用于确认的预览指纹
     */
    @PostMapping("/initialize/preview")
    public R<Preview> previewInitialize(@Valid @RequestBody Initialize request) {
        permit(TRADING_CALENDAR_INIT);
        return R.ok(service.previewInitialize(request));
    }

    /**
     * 校验预览指纹后写入年度初始化计划。
     *
     * @param request 年份、市场集合和预览指纹
     * @return 实际应用的初始化计划摘要
     */
    @PostMapping("/initialize/apply")
    public R<Preview> applyInitialize(@Valid @RequestBody Initialize request) {
        permit(TRADING_CALENDAR_INIT);
        return R.ok(service.applyInitialize(request).preview());
    }

    /**
     * 预览手工设置交易状态和假日名称的影响。
     *
     * @param request 指定市场、日期及目标状态的批量规则
     * @return 计划影响数量和确认指纹
     */
    @PostMapping("/batch/preview")
    public R<Preview> previewBatch(@Valid @RequestBody Batch request) {
        permit(TRADING_CALENDAR_EDIT);
        return R.ok(service.previewBatch(request));
    }

    /**
     * 在同一事务内应用当前指纹对应的批量设置。
     *
     * @param request 批量规则及与预览一致的指纹
     * @return 实际应用的批量变更摘要
     */
    @PostMapping("/batch/apply")
    public R<Preview> applyBatch(@Valid @RequestBody Batch request) {
        permit(TRADING_CALENDAR_EDIT);
        return R.ok(service.applyBatch(request).preview());
    }

    /**
     * 下载交易日历 Excel 导入模板。
     *
     * @param mode 单市场或多市场导入模式
     * @return 按模式命名的 XLSX 附件
     */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template(@RequestParam ImportMode mode) {
        permit(TRADING_CALENDAR_IMPORT);
        String filename = mode == ImportMode.SINGLE ? "trading-calendar-single.xlsx" : "trading-calendar-multi.xlsx";
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(excel.template(mode.name()));
    }

    /**
     * 解析 Excel 并预览变更，保留可定位到行的校验问题。
     *
     * @param file       待导入的 XLSX 文件
     * @param mode       单市场或多市场导入模式
     * @param marketCode 单市场模式指定的市场编码
     * @return 行问题、影响数量及可用时的预览指纹
     */
    @PostMapping(path = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Preview> previewImport(@RequestParam MultipartFile file, @RequestParam ImportMode mode,
                                    @RequestParam(required = false) String marketCode) {
        permit(TRADING_CALENDAR_IMPORT);
        return R.ok(service.previewImport(parse(file, mode, marketCode)));
    }

    /**
     * 重新解析文件并校验预览指纹，确认计划后写入。
     *
     * @param file        与预览一致的 XLSX 文件
     * @param mode        单市场或多市场导入模式
     * @param marketCode  单市场模式指定的市场编码
     * @param fingerprint 该文件和当前日历状态对应的预览指纹
     * @return 实际应用的导入计划摘要
     */
    @PostMapping(path = "/import/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Preview> applyImport(@RequestParam MultipartFile file, @RequestParam ImportMode mode,
                                  @RequestParam(required = false) String marketCode,
                                  @RequestParam(required = false) String fingerprint) {
        permit(TRADING_CALENDAR_IMPORT);
        return R.ok(service.applyImport(parse(file, mode, marketCode), fingerprint).preview());
    }

    private TradingCalendarExcel.Parsed parse(MultipartFile file, ImportMode mode, String marketCode) {
        try (var input = file.getInputStream()) {
            return excel.parse(input, mode.name(), marketCode);
        } catch (IOException e) {
            throw BadRequestException.localized("error.trading-calendar.file");
        }
    }

    private static <T> R<T> read(String path, Supplier<T> employeeRead, Supplier<T> applicationRead) {
        return R.ok((permitRead(path) ? applicationRead : employeeRead).get());
    }

    private static boolean permitRead(String path) {
        if (ManagedReadInvocation.callerFor(TradingCalendarController.class, ROOT_PATH + path) != null) {
            return true;
        }
        permit(TRADING_CALENDAR_MGT);
        return false;
    }

    private static void permit(String code) {
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(TRADING_CALENDAR_MGT);
            if (!TRADING_CALENDAR_MGT.equals(code)) {
                StpUtil.checkPermission(code);
            }
        }
    }
}
