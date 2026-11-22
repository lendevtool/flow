package me.lendev.flow.workbench.todo;

import static me.lendev.flow.common.auth.PermissionCode.*;
import static me.lendev.flow.workbench.todo.TodoAccess.permitRead;
import static me.lendev.flow.workbench.todo.TodoModels.*;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.R;
import me.lendev.flow.openapi.ManagedReadInvocation;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工作台待办列表、日历、动作和维度 HTTP 接口。
 */
@RestController
@RequestMapping(TodoController.ROOT_PATH)
@RequiredArgsConstructor
public class TodoController {

    static final String ROOT_PATH = "/workbench/todos";
    static final String PUBLIC_DIMENSIONS_PATH = "/public-dimensions";

    private final TodoService service;
    private final TodoMutations mutations;
    private final TodoSchedule schedule;
    private final TodoPersonalDimensions personal;
    private final TodoPublicDimensions publicDimensions;

    /**
     * 分页查询待办列表，附带各范围计数。
     */
    @GetMapping
    public R<TodoPage> list(@RequestParam MultiValueMap<String, String> params) {
        TodoAccess.permit(WORKBENCH_TODO);
        return R.ok(service.list(TodoQuery.from(params), TodoAccess.viewer()));
    }

    /**
     * 日历视图查询，区间跨度须小于 42 天。
     */
    @GetMapping("/calendar")
    public R<List<CalendarTodo>> calendar(@RequestParam MultiValueMap<String, String> params) {
        TodoAccess.permit(WORKBENCH_TODO_CALENDAR);
        return R.ok(service.calendar(TodoQuery.from(params), TodoAccess.viewer()));
    }

    /**
     * 按公共或个人维度槽位分组统计。
     */
    @GetMapping("/statistics")
    public R<List<Statistic>> statistics(@RequestParam MultiValueMap<String, String> params) {
        TodoAccess.permit(WORKBENCH_TODO);
        return R.ok(service.statistics(TodoQuery.from(params), params.getFirst("groupBy"), TodoAccess.viewer()));
    }

    /**
     * 查询本人可见的待办详情。
     */
    @GetMapping("/{id}")
    public R<Todo> detail(@PathVariable long id) {
        permitRead();
        return R.ok(service.detail(id, TodoAccess.viewer()));
    }

    /**
     * 管理身份查询任意待办详情，需待办管理权限。
     */
    @GetMapping("/manage/{id}")
    public R<Todo> managedDetail(@PathVariable long id) {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(WORKBENCH_TODO_MANAGE);
        return R.ok(mutations.get(id));
    }

    /**
     * 管理身份改派待办负责人。
     */
    @PostMapping("/manage/{id}/assign")
    public R<Todo> managedAssign(@PathVariable long id, @RequestBody Action request) {
        TodoAccess.permit(WORKBENCH_TODO);
        return R.ok(mutations.manageAssign(id, request).after());
    }

    /**
     * 手动创建待办，须携带幂等请求键。
     */
    @PostMapping
    public R<Todo> create(@RequestBody Write request) {
        permitRead();
        TodoAccess.permit(WORKBENCH_TODO_CREATE);
        return R.ok(service.create(request, TodoAccess.viewer()));
    }

    /**
     * 部分更新待办，请求体须携带当前版本号。
     */
    @PatchMapping("/{id}")
    public R<Todo> edit(@PathVariable long id, @RequestBody ObjectNode request) {
        permitRead();
        int viewer = TodoAccess.viewer();
        return R.ok(service.withCurrentViewer(mutations.edit(id, request, viewer).after(), viewer));
    }

    /**
     * 执行待办动作：start/complete/cancel/assign，其余动作名视为参数错误。
     */
    @PostMapping("/{id}/actions/{action}")
    public R<Todo> act(@PathVariable long id, @PathVariable String action, @RequestBody Action request) {
        permitRead();
        int viewer = TodoAccess.viewer();
        Todo result = switch (action) {
            case "start" -> mutations.start(id, request, viewer).after();
            case "complete" -> mutations.complete(id, request, viewer).after();
            case "cancel" -> mutations.cancel(id, request, viewer).after();
            case "assign" -> mutations.assign(id, request, viewer).after();
            default -> throw BadRequestException.localized("error.todo.request");
        };
        return R.ok(service.withCurrentViewer(result, viewer));
    }

    /**
     * 预览交易排期解析结果，提交时须原样携带以确认日历未变化。
     */
    @PostMapping("/schedule-preview")
    public R<SchedulePreview> preview(@RequestBody Write request) {
        permitRead();
        return R.ok(schedule.preview(request));
    }

    /**
     * 查询可选负责人：无改派/管理权限时仅返回本人。
     */
    @GetMapping("/assignees")
    public R<List<Person>> assignees() {
        permitRead();
        if (!TodoAccess.can(WORKBENCH_TODO_ASSIGN) && !TodoAccess.can(WORKBENCH_TODO_FLOW_MANAGE)
                && !TodoAccess.can(WORKBENCH_TODO_MANAGE)) {
            return R.ok(List.of(service.requireUser(TodoAccess.viewer())));
        }
        return R.ok(service.assignees());
    }

    @GetMapping(PUBLIC_DIMENSIONS_PATH)
    public R<List<TodoPublicDimensions.Dimension>> publicDimensions() {
        if (ManagedReadInvocation.callerFor(TodoController.class, ROOT_PATH + PUBLIC_DIMENSIONS_PATH) == null) {
            permitRead();
        }
        return R.ok(publicDimensions.list());
    }

    @GetMapping("/personal-dimensions")
    public R<List<PersonalDimension>> personalDimensions() {
        permitRead();
        return R.ok(personal.list(TodoAccess.viewer()));
    }

    /**
     * 新建或更新个人维度槽位配置。
     */
    @PutMapping("/personal-dimensions/{slot}")
    public R<PersonalDimension> configure(@PathVariable int slot, @RequestBody PersonalWrite request) {
        permitRead();
        return R.ok(personal.configure(slot, request).saved());
    }

    /**
     * 更新待办个人标记，值为 null 表示清除槽位。
     */
    @PatchMapping("/{id}/personal-dimensions")
    public R<Todo> tag(@PathVariable long id, @RequestBody Map<Integer, String> request) {
        permitRead();
        personal.tag(id, request);
        return R.ok(service.detail(id, TodoAccess.viewer()));
    }
}
