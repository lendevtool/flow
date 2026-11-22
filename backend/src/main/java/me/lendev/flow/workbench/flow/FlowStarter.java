package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.FlowModels.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.workbench.todo.TodoModels.Todo;
import me.lendev.flow.workbench.todo.TodoModels.Person;
import me.lendev.flow.workbench.todo.TodoModels.Write;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoPublicDimensions;
import me.lendev.flow.workbench.todo.TodoSchedule;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发起流程的独立审计写入入口。请求键冲突由外层在事务结束后复核。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class FlowStarter {

    private final FlowRepository repository;
    private final TodoService todos;
    private final TodoPublicDimensions dimensions;
    private final TodoSchedule schedule;
    private final FlowAuditHandler audit;
    private final WorkflowRuntimeNotices notices;

    /**
     * 节点激活结果：更新后的节点状态与生成的待办。
     */
    record Activated(Node node, Todo todo) {

    }

    /**
     * 写入流程与首个节点待办，同时提交或回滚。
     */
    @Audited(AuditOperation.TODO_FLOW_START)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.RunChange start(Launch launch) {
        return startNew(launch);
    }

    /**
     * 在调用方聚合审计事务内发起流程，审计事实并入外层事务。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public FlowAuditHandler.RunChange startInAggregate(Launch launch) {
        var change = startNew(launch);
        if (!change.after().equals(change.before())) {
            AuditParticipants.addChanges(audit.runChanges(change));
        }
        return change;
    }

    private FlowAuditHandler.RunChange startNew(Launch launch) {
        var request = launch.request();
        var template = repository.definition(request.definitionId(), true).orElseThrow(TodoNotFoundException::new);
        var userIds = assigneeIds(request.assignees(), template);
        userIds.forEach(launch.authorize()::accept);
        var previous = repository.byRequest(launch.scope(), request.requestKey());
        if (previous.isPresent()) {
            return FlowAuditHandler.RunChange.replay(FlowService.replay(previous.get(), launch.hash()));
        }
        if (template.status() != DefinitionStatus.PUBLISHED) {
            throw BadRequestException.localized("error.flow.definition.unpublished");
        }
        List<Node> nodes = assignments(userIds, template);
        String ownerName = launch.owner() == null ? null : todos.requireUser(launch.owner()).name();
        dimensions.validate(request.dimensions(), Map.of(), false);
        var initial = new Run(0, template.id(), request.title(), launch.owner(), ownerName, launch.source(),
                request.dimensions(), nodes, 1, RunStatus.RUNNING, 0, null);
        var now = schedule.now();
        long id = repository.createRun(initial, launch.scope(), request.requestKey(), launch.hash(), now);
        log.info("流程实例创建，实例 ID={}，定义 ID={}，步骤数={}", id, template.id(), nodes.size());
        var pending = new Run(id, template.id(), request.title(), launch.owner(), ownerName, launch.source(),
                request.dimensions(), nodes, 1, RunStatus.RUNNING, 0, null);
        var activated = activate(pending, nodes.getFirst());
        var activeNodes = new ArrayList<>(nodes);
        activeNodes.set(0, activated.node());
        var active = new Run(id, template.id(), request.title(), launch.owner(), ownerName, launch.source(),
                request.dimensions(), List.copyOf(activeNodes), 1, RunStatus.RUNNING, 0, null);
        repository.writeRun(active, 0, now);
        return new FlowAuditHandler.RunChange(null, active, List.of(new FlowAuditHandler.NodeChange(null, activated.todo())));
    }

    /**
     * 合并模板默认分工与发起时的改派覆盖，返回各步骤负责人 ID。
     *
     * @throws BadRequestException 步骤号越界或存在未指定负责人的步骤
     */
    static List<Integer> assigneeIds(Map<Integer, Integer> overrides, Definition template) {
        if (overrides.keySet().stream().anyMatch(number -> number < 1 || number > template.steps().size())) {
            throw BadRequestException.localized("error.flow.steps");
        }
        return template.steps().stream().map(step -> {
            Integer id = overrides.getOrDefault(step.stepNo(), step.assigneeUserId());
            if (id == null) {
                throw BadRequestException.localized("error.flow.assignees");
            }
            return id;
        }).toList();
    }

    private List<Node> assignments(List<Integer> userIds, Definition template) {
        var people = new HashMap<Integer, Person>();
        userIds.stream().distinct().sorted().forEach(id -> people.put(id, todos.requireActiveUser(id)));
        List<Node> nodes = new ArrayList<>();
        for (Step step : template.steps()) {
            var person = people.get(userIds.get(step.stepNo() - 1));
            nodes.add(new Node(step.stepNo(), step.title(), person.id(), person.name(), step.dueOffsetDays(),
                    null, null, StepStatus.WAITING, null));
        }
        return List.copyOf(nodes);
    }

    /**
     * 在调用方持有流程事务时创建节点，并固定激活标识和截止日期。
     * 激活标识为随机 UUID，保证同一节点重复激活时待办幂等。
     */
    Activated activate(Run flow, Node node) {
        String key = UUID.randomUUID().toString();
        LocalDate due = node.dueOffsetDays() == null ? null : schedule.today().plusDays(node.dueOffsetDays());
        var request = new Write(null, node.title(), null, node.assigneeUserId(), "NORMAL",
                due == null ? "NONE" : "FIXED", null, null, due, null, null, null, null, null,
                flow.dimensions(), null, null);
        Todo todo = todos.createFlowNode(flow.id(), key, flow.ownerUserId(), flow.ownerName(), request);
        notices.legacyAssigned(todo.id(), todo.assigneeUserId(), node.title());
        log.info("流程节点激活，实例 ID={}，步骤号={}，待办 ID={}", flow.id(), node.stepNo(), todo.id());
        return new Activated(node.activated(key, todo.id(), todo.assigneeName()), todo);
    }
}
