package me.lendev.flow.calendar;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.calendar.OpenTradingCalendarModels.Day;
import me.lendev.flow.calendar.OpenTradingCalendarModels.Market;
import me.lendev.flow.calendar.OpenTradingCalendarModels.Offset;
import me.lendev.flow.openapi.OpenApiEndpoint;
import me.lendev.flow.openapi.OpenApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供交易日历开放 API v1 的只读入口。调用方须提交有效的应用 Bearer 令牌，
 * 并具备目标接口的授权范围。成功时直接返回 JSON 对象或数组，失败时返回开放 API 的 HTTP 状态和错误码。
 * 共用鉴权失败包括 401 {@code INVALID_TOKEN}、403 {@code INSUFFICIENT_SCOPE} 和 429 {@code RATE_LIMITED}。
 */
@RestController
@RequestMapping("/open/v1/trading-calendar")
@RequiredArgsConstructor
@Tag(name = "交易日历")
@SecurityRequirement(name = "appBearer")
public class OpenTradingCalendarController {

    private final OpenTradingCalendarService service;

    /**
     * 查询当前启用的市场列表。
     *
     * @return 以参数明细顺序排列的市场编码、中英文名称和时区列表，无启用市场时为空数组
     */
    @Operation(summary = "市场列表")
    @OpenApiEndpoint("trading-calendar.markets.read")
    @GetMapping("/markets")
    public List<Market> markets() {
        return service.markets();
    }

    /**
     * 查询指定市场在自然年度内已配置的交易日历。
     *
     * @param year        必填查询参数，自然年，范围为 1000 至 9999
     * @param marketCodes 必填查询参数，逗号分隔的 1 至 32 个启用市场编码，编码忽略大小写和首尾空白
     * @return 按市场参数项 ID、日期升序排列的日历记录，重复市场只查询一次，未覆盖日期省略，无记录时为空数组
     * @throws OpenApiException 年份、市场数量或市场编码不合法时返回 400 {@code INVALID_ARGUMENT}
     */
    @Operation(summary = "年度交易日历")
    @OpenApiEndpoint("trading-calendar.year.read")
    @GetMapping("/year")
    public List<Day> year(@RequestParam @Parameter(schema = @Schema(minimum = "1000", maximum = "9999")) int year,
                          @RequestParam @Parameter(description = "逗号分隔的 1 至 32 个市场编码", example = "CN,HK") String marketCodes) {
        return service.year(year, List.of(marketCodes.split(",", -1)));
    }

    /**
     * 查询指定市场在一个自然日的交易状态。
     *
     * @param marketCode 必填查询参数，启用的市场编码，忽略大小写和首尾空白
     * @param date       必填查询参数，市场所在时区的自然日，格式为 {@code uuuu-MM-dd}，年份范围为 1000 至 9999
     * @return 已配置日期的开休市状态和可空的假日名称
     * @throws OpenApiException 市场或日期不合法时返回 400 {@code INVALID_ARGUMENT}，日期缺少覆盖时返回
     *                          404 {@code CALENDAR_COVERAGE_MISSING}
     */
    @Operation(summary = "指定日期交易状态")
    @OpenApiEndpoint("trading-calendar.status.read")
    @GetMapping("/status")
    public Day status(@RequestParam String marketCode, @RequestParam @DateTimeFormat(pattern = "uuuu-MM-dd") LocalDate date) {
        return service.status(marketCode, date);
    }

    /**
     * 从已覆盖的基准日期起，按市场配置计算交易日偏移。非零偏移从相邻日期开始计数。
     *
     * @param marketCode 必填查询参数，启用的市场编码，忽略大小写和首尾空白
     * @param date       必填查询参数，基准日期，格式为 {@code uuuu-MM-dd}，年份范围为 1000 至 9999
     * @param n          必填查询参数，范围为 -366 至 366，正数向未来计数，负数向过去计数，零要求基准日开市
     * @return 命中的目标交易日
     * @throws OpenApiException 参数不合法时返回 400 {@code INVALID_ARGUMENT}，扫描遇到覆盖缺口时返回
     *                          404 {@code CALENDAR_COVERAGE_MISSING}，零偏移的基准日休市时返回
     *                          400 {@code CALENDAR_BASE_CLOSED}
     */
    @Operation(summary = "交易日偏移")
    @OpenApiEndpoint("trading-calendar.offset.read")
    @GetMapping("/offset")
    public Offset offset(@RequestParam String marketCode, @RequestParam @DateTimeFormat(pattern = "uuuu-MM-dd") LocalDate date,
                         @RequestParam @Parameter(schema = @Schema(minimum = "-366", maximum = "366")) int n) {
        return service.offset(marketCode, date, n);
    }
}
