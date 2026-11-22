package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.FlowModels.*;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台流程模板管理、流程发起及节点动作接口。
 */
@RestController
@RequestMapping("/workbench")
@RequiredArgsConstructor
@Tag(name = "工作台流程")
class FlowController {

    private final FlowService service;

    /**
     * 查询流程模板，published=true 供发起人选择已发布版本。
     */
    @GetMapping("/flow-definitions")
    R<List<Definition>> definitions(@RequestParam(defaultValue = "false") boolean published) {
        return R.ok(service.definitions(published));
    }

    /**
     * 创建模板草稿。
     */
    @PostMapping("/flow-definitions")
    R<Definition> createDefinition(@Valid @RequestBody CreateDefinition request) {
        return R.ok(service.createDefinition(request).after());
    }

    /**
     * 编辑草稿模板，须携带当前版本。
     */
    @PatchMapping("/flow-definitions/{id}")
    R<Definition> editDefinition(@PathVariable long id, @Valid @RequestBody EditDefinition request) {
        return R.ok(service.editDefinition(id, request).after());
    }

    /**
     * 发布模板并冻结内容。
     */
    @PostMapping("/flow-definitions/{id}/publish")
    R<Definition> publish(@PathVariable long id, @Valid @RequestBody Version request) {
        return R.ok(service.publish(id, request).after());
    }

    /**
     * 停用模板版本。
     */
    @PostMapping("/flow-definitions/{id}/retire")
    R<Definition> retire(@PathVariable long id, @Valid @RequestBody Version request) {
        return R.ok(service.retire(id, request).after());
    }

    /**
     * 发起流程，请求键幂等。
     */
    @PostMapping("/flows")
    R<Progress> start(@Valid @RequestBody Start request) {
        return R.ok(service.start(request));
    }

    /**
     * 查询流程进度详情。
     */
    @GetMapping("/flows/{id}")
    R<Progress> detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    /**
     * 完成当前节点并推进流程。
     */
    @PostMapping("/flows/{id}/complete")
    R<Progress> complete(@PathVariable long id, @Valid @RequestBody Complete request) {
        return R.ok(service.currentViewerProgress(service.complete(id, request).after()));
    }

    /**
     * 取消运行中的流程。
     */
    @PostMapping("/flows/{id}/cancel")
    R<Progress> cancel(@PathVariable long id, @Valid @RequestBody Cancel request) {
        return R.ok(service.currentViewerProgress(service.cancel(id, request).after()));
    }

    /**
     * 改派当前或未来步骤负责人。
     */
    @PostMapping("/flows/{id}/assign")
    R<Progress> assign(@PathVariable long id, @Valid @RequestBody Assign request) {
        return R.ok(service.currentViewerProgress(service.assign(id, request).after()));
    }
}
