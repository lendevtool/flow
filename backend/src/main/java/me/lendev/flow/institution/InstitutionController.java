package me.lendev.flow.institution;

import static me.lendev.flow.institution.InstitutionAccess.*;
import static me.lendev.flow.institution.InstitutionModels.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.workbench.flow.WorkflowBusinessService;
import org.springframework.web.bind.annotation.*;

/**
 * 「业务信息 → 机构管理」的机构维护与审批申请接口。
 */
@RestController
@RequestMapping("/institutions")
@RequiredArgsConstructor
public class InstitutionController {

    private final InstitutionService institutions;
    private final InstitutionFields fields;
    private final WorkflowBusinessService workflows;

    /**
     * 读取机构编辑选项和当前已发布的审批模板。
     *
     * @return 机构档案和申请表单所需的配置
     */
    @GetMapping("/options")
    public R<Options> options() {
        permit(VIEW);
        return R.ok(fields.options(workflows.publishedOptions(InstitutionWorkflow.TEMPLATE)));
    }

    /**
     * 保存新的机构档案。
     *
     * @param request 机构名称及配置允许写入的字段
     * @return 新建的机构档案
     */
    @PostMapping
    public R<Institution> create(@Valid @RequestBody Save request) {
        return R.ok(institutions.create(request, permit(CREATE)).value());
    }

    /**
     * 按档案版本更新机构字段。
     *
     * @param id      机构档案 ID
     * @param request 当前档案版本和待更新的档案内容
     * @return 更新后的机构档案
     */
    @PutMapping("/{id}")
    public R<Institution> edit(@PathVariable long id, @Valid @RequestBody Save request) {
        return R.ok(institutions.edit(id, request, permit(EDIT)).value());
    }

    /**
     * 按档案版本变更机构启停状态。
     *
     * @param id      机构档案 ID
     * @param request 当前档案版本及目标启用状态
     * @return 更新后的机构档案
     */
    @PutMapping("/{id}/status")
    public R<Institution> status(@PathVariable long id, @Valid @RequestBody StatusEdit request) {
        return R.ok(institutions.status(id, request, permit(STATUS)).value());
    }

    /**
     * 分页查询指定机构的变更申请。
     *
     * @param id       机构档案 ID
     * @param pageNo   页码，从 1 开始，默认 1
     * @param pageSize 每页条数，默认 20，最多 100
     * @return 变更申请的当前页和总数
     */
    @GetMapping("/{id}/changes")
    public R<Page<Change>> changes(@PathVariable long id,
                                   @RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        permit(VIEW);
        return R.ok(institutions.changes(id, pageNo, pageSize));
    }

    /**
     * 读取一条归属于指定机构的变更申请。
     *
     * @param id       机构档案 ID
     * @param changeId 变更申请 ID
     * @return 申请详情和关联的流程状态
     */
    @GetMapping("/{id}/changes/{changeId}")
    public R<Change> change(@PathVariable long id, @PathVariable long changeId) {
        permit(VIEW);
        return R.ok(institutions.change(id, changeId));
    }

    /**
     * 建立机构变更申请并发起审批流程。
     *
     * @param id    目标机构档案 ID
     * @param input 申请依据的档案版本、变更理由和拟修改字段
     * @return 记录了审批实例引用的变更申请
     */
    @PostMapping("/{id}/changes")
    public R<Change> submit(@PathVariable long id, @Valid @RequestBody Submit input) {
        return R.ok(institutions.submit(id, input, permit(SUBMIT)));
    }
}
