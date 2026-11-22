package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_CREATE;
import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.workbench.flow.WorkflowInstanceQueries.summary;
import static me.lendev.flow.workbench.flow.WorkflowRuns.REQUEST_KEY_CONFLICT;
import static me.lendev.flow.workbench.flow.WorkflowRuns.conflict;
import static me.lendev.flow.workbench.flow.WorkflowRuns.event;
import static me.lendev.flow.workbench.flow.WorkflowRuns.verifyVersion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.field.FieldModels;
import me.lendev.flow.field.FieldValidator;
import me.lendev.flow.workbench.flow.FlowModels.DefinitionStatus;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.FieldBinding;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.View;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Detail;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Start;
import me.lendev.flow.workbench.flow.entity.FlowInstance;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoPublicDimensions;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 发起流程和处理业务方取消，维护模板绑定及请求幂等。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowLifecycleService {

    private final WorkflowRuntimeRepository repository;
    private final WorkflowDefinitionRepository definitions;
    private final WorkflowBusinessRegistry modules;
    private final WorkflowPeople people;
    private final WorkflowRuns runs;
    private final WorkflowStages stages;
    private final WorkflowQueries queries;
    private final FieldValidator validator;
    private final TodoService todos;
    private final TodoPublicDimensions dimensions;

    /**
     * 按新版发起请求锁定模板。同一模板上的重试共享同一事务顺序。
     * 请求键在发起人范围内幂等，同键不同内容抛出冲突。
     *
     * @throws TodoConflictException           请求键重复但内容不一致
     * @throws WorkflowPrimaryHandlerException 主办配置无法分派
     */
    @Audited(AuditOperation.TODO_FLOW_START)
    @Transactional(rollbackFor = Exception.class)
    public Detail start(Start request) {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(WORKBENCH_TODO_CREATE);
        int actor = TodoAccess.viewer();
        String scope = "USER:" + actor;
        String hash = runs.hash(List.of(request.definitionId(), request.title().strip()));
        var definition = definitions.find(request.definitionId(), true).orElseThrow(TodoNotFoundException::new);
        var prior = repository.findRequest(scope, request.requestKey());
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().getRequestHash())) {
                throw conflict(REQUEST_KEY_CONFLICT);
            }
            return queries.detail(runs.load(prior.get(), definition), actor);
        }
        if (modules.businessOnlyTemplates().contains(definition.code())) {
            throw localized("error.workflow.template.business-only");
        }
        var flow = create(definition, new RequestIdentity(scope, request.requestKey(), hash), request.title().strip(),
                actor, null, Map.of(), Map.of());
        log.info("工作台角色流程发起，instanceId={}，definitionId={}，发起人 ID={}", flow.row().getId(),
                request.definitionId(), actor);
        return queries.detail(flow, actor);
    }

    /**
     * 业务独立操作或外层普通事务中的发起入口。
     */
    @Audited(AuditOperation.TODO_FLOW_START)
    @Transactional(rollbackFor = Exception.class)
    public WorkflowBusinessService.Summary startFromBusiness(WorkflowBusinessSource module, Source source,
                                                             WorkflowBusinessService.Start request) {
        return businessStart(module, source, request);
    }

    /**
     * 业务已开启审计时，节点和事件加入该聚合审计。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WorkflowBusinessService.Summary startInAggregate(WorkflowBusinessSource module, Source source,
                                                            WorkflowBusinessService.Start request) {
        return businessStart(module, source, request);
    }

    private WorkflowBusinessService.Summary businessStart(WorkflowBusinessSource module, Source source,
                                                          WorkflowBusinessService.Start request) {
        String scope = "MODULE:" + source.module();
        String hash = runs.hash(List.of(source, request));
        var prior = repository.findRequest(scope, request.requestKey());
        if (prior.isPresent()) {
            return replayBusiness(module, source, prior.get(), hash);
        }
        if (!module.mayStart(source, request.templateCode())) {
            throw localized("error.todo.source");
        }
        var definition = definitions.published(request.templateCode(), request.revision())
                .orElseThrow(() -> localized("error.flow.definition.unpublished"));
        prior = repository.findBusinessRequest(scope, request.requestKey());
        if (prior.isPresent()) {
            return replayBusiness(module, source, prior.get(), hash);
        }
        if (!module.inputFields(source, request.templateCode()).containsAll(request.fields().keySet())
                || !definition.fields().stream().map(FieldBinding::code).toList().containsAll(request.fields().keySet())) {
            throw localized("error.workflow.fields");
        }
        for (var field : definition.fields()) {
            if (request.fields().containsKey(field.code())) {
                var value = request.fields().get(field.code());
                if (field.config().dataType() == FieldModels.DataType.ATTACHMENT && value != null && !value.isNull()) {
                    throw localized("error.workflow.attachment");
                }
                validator.value(field.config(), value, field.options().stream().map(FieldModels.Option::code)
                        .collect(Collectors.toSet()), false);
            }
        }
        dimensions.validate(request.dimensions(), Map.of(), false);
        var flow = create(definition, new RequestIdentity(scope, request.requestKey(), hash), request.title(), source.ownerId(), source,
                request.fields(), request.dimensions());
        log.info("业务模块角色流程发起，模块={}，业务 ID={}，instanceId={}", source.module(), source.id(),
                flow.row().getId());
        return summary(flow.row(), definition);
    }

    private WorkflowBusinessService.Summary replayBusiness(WorkflowBusinessSource module, Source source,
                                                           FlowInstance prior, String hash) {
        if (source.ownerId() != null && !module.mayRead(source, source.ownerId())) {
            throw new TodoNotFoundException();
        }
        if (!hash.equals(prior.getRequestHash())) {
            log.info("业务流程幂等回放失败，模块={}，instanceId={}", source.module(), prior.getId());
            throw conflict(REQUEST_KEY_CONFLICT);
        }
        return summary(prior, definitions.find(prior.getDefinitionId(), false).orElseThrow(TodoNotFoundException::new));
    }

    /**
     * 发起请求的幂等标识：作用域、请求键与内容摘要。
     */
    private record RequestIdentity(String scope, String key, String hash) {

    }

    /**
     * 校验主办配置后写入实例主行、激活首阶段并记录 STARTED 事件。
     * 并发请求键冲突由唯一约束兜底。
     */
    private WorkflowRun create(View definition, RequestIdentity request, String title, Integer owner,
                               Source source, Map<String, JsonNode> fields, Map<Integer, Long> shared) {
        if (definition.status() != DefinitionStatus.PUBLISHED) {
            throw localized("error.flow.definition.unpublished");
        }
        var primaryHandlers = people.requirePrimaryHandlers(definition.nodes());
        var now = people.now();
        var row = new FlowInstance();
        row.setDefinitionId(definition.id());
        row.setOwnerUserId(owner);
        row.setOwnerName(owner == null ? null : todos.requireActiveUser(owner).name());
        row.setTitle(title);
        if (source != null) {
            row.setSourceModule(source.module());
            row.setBusinessType(source.type());
            row.setBusinessId(source.id());
        }
        row.setRequestScope(request.scope());
        row.setRequestKey(request.key());
        row.setRequestHash(request.hash());
        row.setCurrentStepNo(1);
        row.setStatus(RunStatus.RUNNING);
        row.setStepsStateJson("[]");
        var values = new LinkedHashMap<String, JsonNode>();
        definition.fields().forEach(field -> {
            var value = field.config().defaultValue();
            if (value != null && !value.isNull()) {
                values.put(field.code(), value);
            }
        });
        values.putAll(fields);
        row.setFieldValuesJson(repository.json(values));
        row.setDimension1ItemId(shared.get(1));
        row.setDimension2ItemId(shared.get(2));
        row.setDimension3ItemId(shared.get(3));
        row.setDimension4ItemId(shared.get(4));
        row.setDimension5ItemId(shared.get(5));
        row.setVersion(0L);
        row.setStartedAt(now);
        row.setUpdatedAt(now);
        try {
            repository.insert(row);
        } catch (DuplicateKeyException e) {
            log.info("流程发起请求键并发冲突，scope={}，requestKey={}", request.scope(), request.key());
            throw conflict(REQUEST_KEY_CONFLICT);
        }
        log.info("流程实例创建，instanceId={}，definitionId={}", row.getId(), definition.id());
        var flow = runs.load(row, definition);
        var target = stages.activate(flow, flow.stage(1), primaryHandlers);
        runs.saveEvent(flow, event("STARTED", null, target, null, null), owner == null ? 0 : owner, " START", request.hash(), now);
        return flow;
    }

    /**
     * 防止业务单操作传入其他业务的实例标识。请求键幂等，重复取消重放当前状态。
     *
     * @throws TodoNotFoundException 实例不存在或来源不匹配
     * @throws TodoConflictException 版本不匹配或流程已结束
     */
    @Audited(AuditOperation.TODO_FLOW_CANCEL)
    @Transactional(rollbackFor = Exception.class)
    public WorkflowBusinessService.Summary cancelFromBusiness(Source source, long id, int actor,
                                                              WorkflowBusinessService.Cancel request) {
        return businessCancel(source, id, actor, request);
    }

    /**
     * 加入调用方的写事务和聚合审计，取消已绑定的流程。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WorkflowBusinessService.Summary cancelInAggregate(Source source, long id, int actor,
                                                             WorkflowBusinessService.Cancel request) {
        return businessCancel(source, id, actor, request);
    }

    private WorkflowBusinessService.Summary businessCancel(Source source, long id, int actor,
                                                           WorkflowBusinessService.Cancel request) {
        var flow = runs.load(id, true);
        if (!WorkflowBusinessService.sameSource(source, flow.source())) {
            throw new TodoNotFoundException();
        }
        String hash = runs.hash(List.of("business-cancel", actor, request));
        if (!runs.replayed(id, request.requestKey(), hash)) {
            verifyVersion(flow, request.version());
            var now = people.now();
            var event = stages.cancelStage(flow, actor, request.reason(), now);
            runs.update(flow, event, actor, request.requestKey(), hash, now);
            log.info("业务流程取消，instanceId={}，操作人 ID={}", id, actor);
        }
        return summary(flow.row(), flow.definition());
    }
}
