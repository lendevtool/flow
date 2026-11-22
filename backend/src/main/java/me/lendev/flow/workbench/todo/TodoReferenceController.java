package me.lendev.flow.workbench.todo;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.calendar.TradingCalendarModels.Market;
import me.lendev.flow.calendar.TradingCalendarService;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.EndpointGrantRead;
import me.lendev.flow.openapi.ManagedReadInvocation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供工作台待办使用的全局交易市场选项。
 */
@RestController
@RequestMapping(TodoController.ROOT_PATH)
@RequiredArgsConstructor
@EndpointGrantRead("workbench")
public class TodoReferenceController {

    private final TradingCalendarService calendar;

    /**
     * 查询当前启用的交易市场，供员工和已获得接口授权的应用使用。
     *
     * @return 交易市场选项
     */
    @GetMapping("/markets")
    public R<List<Market>> markets() {
        if (ManagedReadInvocation.callerForModule(TodoReferenceController.class) == null) {
            TodoAccess.permitRead();
        }
        return R.ok(calendar.markets());
    }
}
