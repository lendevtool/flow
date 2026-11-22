package me.lendev.flow.workbench.flow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import org.springframework.web.bind.annotation.*;

/**
 * 已登记业务模块的流程入口，来源和发起人由路径及认证身份确定。
 */
@RestController
@RequestMapping("/workflow/business/{module}/{businessType}/{businessId}/instances")
@RequiredArgsConstructor
class WorkflowBusinessController {

    private final WorkflowBusinessService service;

    /**
     * 业务单发起流程，请求键幂等。
     */
    @PostMapping
    R<WorkflowBusinessService.Summary> start(@PathVariable String module, @PathVariable String businessType,
                                             @PathVariable String businessId,
                                             @Valid @RequestBody WorkflowBusinessService.Start request) {
        return R.ok(service.startFromBusiness(source(module, businessType, businessId), request));
    }

    /**
     * 按业务读权限分页查询关联实例。
     */
    @GetMapping
    R<Page<WorkflowBusinessService.Summary>> list(@PathVariable String module, @PathVariable String businessType,
                                                  @PathVariable String businessId,
                                                  @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        int viewer = TodoAccess.viewer();
        return R.ok(service.findBySource(source(module, businessType, businessId), viewer, pageNo, pageSize));
    }

    /**
     * 业务单授权后取消流程。
     */
    @PostMapping("/{id}/cancel")
    R<WorkflowBusinessService.Summary> cancel(@PathVariable String module, @PathVariable String businessType,
                                              @PathVariable String businessId, @PathVariable long id,
                                              @Valid @RequestBody WorkflowBusinessService.Cancel request) {
        return R.ok(service.cancelFromBusiness(source(module, businessType, businessId), id,
                TodoAccess.viewer(), request));
    }

    private static Source source(String module, String type, String id) {
        return new Source(module, type, id, TodoAccess.viewer());
    }
}
