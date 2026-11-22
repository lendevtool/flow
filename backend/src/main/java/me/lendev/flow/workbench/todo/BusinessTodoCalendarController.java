package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.CONTEXT_ID;
import static me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.page;
import static me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.single;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Option;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarModels.*;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登记业务场景的只读待办日历接口。
 */
@RestController
@RequestMapping("/workbench/business-todo-calendars/{sceneCode}")
@RequiredArgsConstructor
public class BusinessTodoCalendarController {

    private final BusinessTodoCalendarService service;

    @ModelAttribute
    void restrictCache(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    }

    /**
     * 读取场景标题、上下文和筛选配置。
     */
    @GetMapping("/meta")
    public R<Meta> meta(@PathVariable String sceneCode, @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.meta(sceneCode, single(parameters, CONTEXT_ID), TodoAccess.viewer()));
    }

    /**
     * 在当前场景可见范围内分页搜索选项。
     */
    @GetMapping("/options/{key}")
    public R<PageResult<Option>> options(@PathVariable String sceneCode, @PathVariable String key,
                                         @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.options(sceneCode, single(parameters, CONTEXT_ID), key,
                single(parameters, "keyword"), BusinessTodoCalendarRequest.selections(parameters.get("value")),
                page(parameters), TodoAccess.viewer()));
    }

    /**
     * 查询周月网格、状态计数和未排期总数。
     */
    @GetMapping("/calendar")
    public R<Calendar> calendar(@PathVariable String sceneCode, @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.calendar(sceneCode, BusinessTodoCalendarRequest.calendar(parameters), TodoAccess.viewer()));
    }

    /**
     * 分页查看范围内、单日或未排期的任务。
     */
    @GetMapping("/items")
    public R<PageResult<Item>> items(@PathVariable String sceneCode, @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.items(sceneCode, BusinessTodoCalendarRequest.items(parameters), TodoAccess.viewer()));
    }

    /**
     * 以已校验的业务范围查看单条任务摘要。
     */
    @GetMapping("/items/{todoId}")
    public R<Detail> detail(@PathVariable String sceneCode, @PathVariable long todoId,
                            @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.detail(sceneCode, single(parameters, CONTEXT_ID), todoId, TodoAccess.viewer()));
    }
}
