package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.WorkflowRuntimeModels.*;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.R;
import me.lendev.flow.workbench.todo.TodoAccess;
import org.springframework.web.bind.annotation.*;

/**
 * 查询和管理个人休假转办，并向转办人补发待办。
 */
@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
@Tag(name = "角色流程运行")
class WorkflowDelegationController {

    private final WorkflowPeople people;
    private final WorkflowTasks tasks;

    /**
     * 查询委托候选人，本人主办角色的备办优先。
     */
    @GetMapping("/delegations/candidates")
    R<List<Member>> candidates(@RequestParam(required = false) @Size(max = 100) String keyword) {
        return R.ok(people.candidates(TodoAccess.viewer(), keyword));
    }

    /**
     * 查询本人委托历史。
     */
    @GetMapping("/delegations")
    R<List<Delegation>> delegations() {
        return R.ok(people.list(TodoAccess.viewer()));
    }

    /**
     * 创建休假转办委托，时段不可与既有委托重叠。
     */
    @PostMapping("/delegations")
    R<Delegation> create(@Valid @RequestBody DelegationWrite request) {
        return R.ok(people.create(TodoAccess.viewer(), request));
    }

    /**
     * 查询委托详情及待补发、已补发待办。
     */
    @GetMapping("/delegations/{id}")
    R<DelegationDetail> delegation(@PathVariable long id) {
        return R.ok(tasks.delegation(id, TodoAccess.viewer()));
    }

    /**
     * 取消委托，终止后续自动双发。
     */
    @PostMapping("/delegations/{id}/cancel")
    R<Delegation> cancel(@PathVariable long id) {
        return R.ok(people.cancel(TodoAccess.viewer(), id));
    }

    /**
     * 向受托人批量补发主办待办，逐条返回结果。
     */
    @PostMapping("/delegations/{id}/forward")
    R<List<ForwardResult>> forward(@PathVariable long id, @Valid @RequestBody Forward request) {
        return R.ok(tasks.forward(id, TodoAccess.viewer(), request));
    }
}
