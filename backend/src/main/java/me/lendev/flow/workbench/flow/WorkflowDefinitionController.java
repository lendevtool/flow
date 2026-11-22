package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.WorkflowDefinitionModels.*;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.field.FieldModels;
import me.lendev.flow.workbench.flow.FlowModels.DefinitionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 新版流程模板管理与可发起模板目录。
 */
@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
@Tag(name = "角色流程模板")
class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;
    private final WorkflowInstanceQueries queries;

    /**
     * 管理视角分页查询模板。
     */
    @GetMapping("/definitions")
    R<Page<View>> definitions(@RequestParam(defaultValue = "false") boolean latestOnly,
                              @RequestParam(required = false) @Size(max = 100) String keyword,
                              @RequestParam(required = false) DefinitionStatus status,
                              @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.page(latestOnly, keyword, status, pageNo, pageSize));
    }

    /**
     * 查询模板详情。
     */
    @GetMapping("/definitions/{id}")
    R<View> definition(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    /**
     * 查询同编码历史修订。
     */
    @GetMapping("/definitions/{id}/history")
    R<Page<View>> history(@PathVariable long id, @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.history(id, pageNo, pageSize));
    }

    /**
     * 管理视角查询模板版本发起的实例。
     */
    @GetMapping("/definitions/{id}/instances")
    R<Page<WorkflowRuntimeModels.Instance>> instances(@PathVariable long id,
                                                      @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(queries.byDefinition(id, pageNo, pageSize));
    }

    /**
     * 查询可配置角色。
     */
    @GetMapping("/definitions/roles")
    R<List<RoleOption>> roles() {
        return R.ok(service.roles());
    }

    /**
     * 查询可配置的流程附加通知场景。
     */
    @GetMapping("/definitions/notification-scenes")
    R<List<NotificationSceneOption>> notificationScenes() {
        return R.ok(service.notificationScenes());
    }

    /**
     * 检查待保存模板的单人节点是否有启用的角色主办。
     */
    @PostMapping("/definitions/validate-primary-handlers")
    R<WorkflowPrimaryHandlerReport> validatePrimaryHandlers(@Valid @RequestBody PrimaryHandlerCheck request) {
        return R.ok(service.validatePrimaryHandlers(request.nodes()));
    }

    /**
     * 分页查询可绑定字段。
     */
    @GetMapping("/definitions/fields")
    R<Page<FieldModels.Definition>> fields(@RequestParam(required = false) @Size(max = 100) String keyword,
                                           @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.fields(keyword, pageNo, pageSize));
    }

    /**
     * 按编码批量查询字段。
     */
    @GetMapping("/definitions/fields/by-code")
    R<List<FieldModels.Definition>> fieldsByCode(
            @RequestParam @Size(max = 100) List<@Size(max = 64) String> codes) {
        return R.ok(service.fieldsByCode(codes));
    }

    /**
     * 创建模板草稿。
     */
    @PostMapping("/definitions")
    R<View> create(@Valid @RequestBody Write request) {
        return R.ok(service.create(request).after());
    }

    /**
     * 编辑草稿，须携带当前版本。
     */
    @PatchMapping("/definitions/{id}")
    R<View> edit(@PathVariable long id, @Valid @RequestBody Write request) {
        return R.ok(service.edit(id, request).after());
    }

    /**
     * 发布模板并冻结字段规则。
     */
    @PostMapping("/definitions/{id}/publish")
    R<View> publish(@PathVariable long id, @Valid @RequestBody FlowModels.Version request) {
        return R.ok(service.publish(id, request.version()).after());
    }

    /**
     * 停用模板版本。
     */
    @PostMapping("/definitions/{id}/retire")
    R<View> retire(@PathVariable long id, @Valid @RequestBody FlowModels.Version request) {
        return R.ok(service.retire(id, request.version()).after());
    }

    /**
     * 查询可发起模板目录。
     */
    @GetMapping("/catalog")
    R<List<View>> catalog() {
        return R.ok(service.catalog());
    }

    /**
     * 分页查询可发起模板目录。
     */
    @GetMapping("/catalog/page")
    R<Page<View>> catalogPage(@RequestParam(defaultValue = "1") @Min(1) int pageNo,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.catalog(pageNo, pageSize));
    }
}
