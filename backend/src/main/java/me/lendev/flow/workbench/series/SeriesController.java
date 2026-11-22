package me.lendev.flow.workbench.series;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_CREATE;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_MANAGE;
import static me.lendev.flow.workbench.series.SeriesModels.*;

import jakarta.validation.Valid;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import me.lendev.flow.workbench.todo.TodoAccess;
import org.springframework.web.bind.annotation.*;

/**
 * 提供本人周期规则查询、排期预览和未来实例变更。
 */
@RestController
@RequestMapping("/workbench/todo-series")
@RequiredArgsConstructor
public class SeriesController {

    private final SeriesService service;

    /**
     * 查询周期规则列表，managed=true 需待办管理权限并返回全部规则。
     */
    @GetMapping
    public R<List<Rule>> list(@RequestParam(defaultValue = "false") boolean managed) {
        TodoAccess.permit(WORKBENCH_TODO);
        if (managed) {
            TodoAccess.permit(WORKBENCH_TODO_MANAGE);
        }
        return R.ok(service.list(managed));
    }

    /**
     * 预览规则生效后的前三期排期，不落库。
     */
    @PostMapping("/preview")
    public R<Preview> preview(@Valid @RequestBody Write request) {
        permitWrite(false);
        return R.ok(service.preview(request));
    }

    /**
     * 创建周期规则，实例由后台调度任务异步生成。
     */
    @PostMapping
    public R<Rule> create(@Valid @RequestBody Write request) {
        permitWrite(false);
        return R.ok(service.create(request).after());
    }

    /**
     * 预览编辑或停止将取消的未来实例，返回提交所需的确认令牌。
     */
    @PostMapping("/{id}/change-preview")
    public R<ChangePreview> changePreview(@PathVariable long id, @Valid @RequestBody ChangeRequest request,
                                          @RequestParam(defaultValue = "false") boolean managed) {
        permitWrite(managed);
        return R.ok(service.changePreview(id, request, managed));
    }

    /**
     * 提交规则编辑，须携带变更预览返回的确认令牌。
     */
    @PatchMapping("/{id}")
    public R<Rule> edit(@PathVariable long id, @Valid @RequestBody EditRequest request,
                        @RequestParam(defaultValue = "false") boolean managed) {
        permitWrite(managed);
        return R.ok(service.edit(id, request, managed).after());
    }

    /**
     * 停止规则并取消未来实例，须携带变更预览返回的确认令牌。
     */
    @PostMapping("/{id}/stop")
    public R<Rule> stop(@PathVariable long id, @Valid @RequestBody StopRequest request,
                        @RequestParam(defaultValue = "false") boolean managed) {
        permitWrite(managed);
        return R.ok(service.stop(id, request, managed).after());
    }

    private static void permitWrite(boolean managed) {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(managed ? WORKBENCH_TODO_MANAGE : WORKBENCH_TODO_CREATE);
    }
}
