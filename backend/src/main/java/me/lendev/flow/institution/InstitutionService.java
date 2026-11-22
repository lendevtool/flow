package me.lendev.flow.institution;

import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.institution.InstitutionFields.*;
import static me.lendev.flow.institution.InstitutionModels.*;
import static me.lendev.flow.institution.InstitutionModels.ChangeStatus.*;
import static me.lendev.flow.institution.InstitutionWorkflow.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.institution.InstitutionAudit.Outcome;
import me.lendev.flow.institution.entity.InstitutionChangeRow;
import me.lendev.flow.institution.entity.InstitutionRow;
import me.lendev.flow.workbench.flow.WorkflowBusinessService;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.EventType;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 编排机构维护、审批发起及结果回写。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class InstitutionService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final InstitutionRepository repository;
    private final InstitutionFields fields;
    private final InstitutionAudit audit;
    private final WorkflowBusinessService workflows;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Page<Institution> page(Query query) {
        return mapPage(repository.page(query), InstitutionService::view);
    }

    Institution detail(long id) {
        return view(require(id, false));
    }

    Page<Change> changes(long id, int page, int size) {
        require(id, false);
        return mapPage(repository.changes(id, page, size), InstitutionService::view);
    }

    Change change(long id, long changeId) {
        var change = repository.change(changeId);
        if (change == null || change.getInstitutionId() != id || change.getFlowInstanceId() == null) {
            throw InstitutionFailure.missing();
        }
        return view(change);
    }

    /**
     * 建立档案并记录业务属性快照。
     */
    @Audited(INSTITUTION_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public Outcome<Institution> create(Save request, int actor) {
        var value = fields.normalize(request);
        fields.validateForm(value, null);
        var row = new InstitutionRow();
        var now = now();
        row.setCode(value.code());
        write(row, value);
        row.setEnabled(true);
        row.setVersion(0L);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            repository.insert(row);
        } catch (DuplicateKeyException duplicate) {
            throw InstitutionFailure.conflict("error.institution.duplicate");
        }
        return edited(null, row);
    }

    /**
     * 直接编辑立即生效，行锁和版本检查防止旧表单覆盖。
     */
    @Audited(INSTITUTION_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public Outcome<Institution> edit(long id, Save request, int actor) {
        var row = require(id, true);
        checkVersion(row, request.version());
        var before = view(row);
        var value = fields.normalize(request);
        if (!before.code().equals(value.code())) {
            throw localized("error.institution.code");
        }
        fields.validateForm(value, row);
        write(row, value);
        if (before.equals(view(row))) {
            return new Outcome<>(before, List.of());
        }
        update(row, actor);
        return edited(before, row);
    }

    /**
     * 切换启停状态并保留业务档案。
     */
    @Audited(INSTITUTION_STATUS)
    @Transactional(rollbackFor = Exception.class)
    public Outcome<Institution> status(long id, StatusEdit input, int actor) {
        var row = require(id, true);
        checkVersion(row, input.version());
        var before = view(row);
        if (before.enabled() == input.enabled()) {
            return new Outcome<>(before, List.of());
        }
        row.setEnabled(input.enabled());
        update(row, actor);
        return edited(before, row);
    }

    /**
     * 以机构行串行化申请。原值快照、流程实例和绑定关系均在同一事务中提交。
     *
     * @param id    机构 ID
     * @param input 档案版本、申请变更的字段和理由
     * @param actor 发起人用户 ID
     * @return 已绑定流程实例的待审批变更单
     */
    @Transactional(rollbackFor = Exception.class)
    public Change submit(long id, Submit input, int actor) {
        var row = require(id, true);
        checkVersion(row, input.version());
        if (repository.pending(id)) {
            log.info("机构已有待审批变更，institutionId={} actorId={}", id, actor);
            throw InstitutionFailure.conflict("error.institution.pending");
        }
        if (!APPROVABLE.containsAll(input.fields().keySet())) {
            throw localized("error.institution.values");
        }
        var context = fields.context(true);
        var current = snapshot(row, context);
        var proposed = new LinkedHashMap<String, JsonNode>();
        input.fields().forEach((code, value) -> {
            var normalized = canonical(value);
            if (!normalized.equals(current.get(code))) {
                proposed.put(code, normalized);
            }
        });
        if (proposed.isEmpty()) {
            throw localized("error.institution.values");
        }
        var base = JsonNodeFactory.instance.objectNode();
        proposed.keySet().forEach(code -> base.set(code, current.get(code)));
        proposed.put(CODE, text(row.getCode()));
        proposed.put(REASON, text(clean(input.reason())));
        fields.validate(proposed, context, Map.of());

        var change = new InstitutionChangeRow();
        change.setInstitutionId(id);
        change.setBaseValues(base);
        change.setStatus(PENDING.name());
        repository.insert(change);
        var source = new Source(MODULE, BUSINESS_TYPE, change.getId().toString(), actor);
        var flow = workflows.startFromBusiness(source, new WorkflowBusinessService.Start(
                "submit:" + change.getId(), TEMPLATE, null, "机构资料变更 · " + row.getCode(), proposed, Map.of()));
        change.setFlowInstanceId(flow.flowInstanceId());
        repository.update(change);
        log.info("机构变更流程绑定已写入，institutionId={} changeId={} instanceId={} actorId={}",
                id, change.getId(), flow.flowInstanceId(), actor);
        return view(change);
    }

    /**
     * 处理已提交的流程终态。相同事件重投只确认回执，档案回写和结果状态在同一事务完成。
     *
     * @param event 机构变更流程的完成或取消事件
     * @return 写入结果和审计变更，重复事件返回空变更列表
     */
    @Audited(INSTITUTION_APPLY)
    @Transactional(rollbackFor = Exception.class)
    public Outcome<Void> apply(Event event) {
        Long id = sourceId(event.source());
        if (id == null || !TEMPLATE.equals(event.templateCode())) {
            return new Outcome<>(null, List.of());
        }
        var claimed = repository.change(id);
        if (claimed == null) {
            throw InstitutionFailure.missing();
        }
        var row = require(claimed.getInstitutionId(), true);
        var change = repository.changeForResult(id);
        if (change == null || !PENDING.name().equals(change.getStatus())
                || !Objects.equals(change.getFlowInstanceId(), Long.valueOf(event.flowInstanceId()))) {
            return new Outcome<>(null, List.of());
        }
        var details = new ArrayList<AuditChange>();
        var result = event.eventType() == EventType.CANCELLED ? CANCELLED : applyApproved(row, change, event, details);
        change.setStatus(result.name());
        change.setResultEventId(Long.valueOf(event.eventId()));
        change.setResolvedAt(now());
        repository.update(change);
        if (!details.isEmpty()) {
            details.add(audit.workflow(change.getId(), change.getFlowInstanceId(), row.getId()));
        }
        log.info("机构变更结果已写入，institutionId={} changeId={} instanceId={} eventId={} status={}",
                row.getId(), id, change.getFlowInstanceId(), event.eventId(), result);
        return new Outcome<>(null, details);
    }

    /**
     * 只比对申请时捕获的字段。原值变化、流程结果缺少字段或当前规则校验失败时，将申请标记为冲突。
     */
    private ChangeStatus applyApproved(InstitutionRow row, InstitutionChangeRow change, Event event, List<AuditChange> details) {
        var context = fields.context(true);
        var current = snapshot(row, context);
        var approved = new LinkedHashMap<String, JsonNode>();
        if (change.getBaseValues().properties().stream().anyMatch(entry ->
                !Objects.equals(current.get(entry.getKey()), entry.getValue()))) {
            return CONFLICT;
        }
        try {
            for (var entry : change.getBaseValues().properties()) {
                if (!event.fields().containsKey(entry.getKey())) {
                    return CONFLICT;
                }
                var value = canonical(event.fields().get(entry.getKey()));
                if (!value.equals(entry.getValue())) {
                    approved.put(entry.getKey(), value);
                }
            }
            fields.validate(approved, context, Map.of());
        } catch (BadRequestException invalidApprovedValues) {
            return CONFLICT;
        }
        if (!approved.isEmpty()) {
            var before = view(row);
            InstitutionFields.apply(row, approved, context);
            int actor = event.actorUserId() != null ? event.actorUserId() : row.getUpdatedBy();
            update(row, actor);
            details.add(audit.change(before, view(row)));
        }
        return APPLIED;
    }

    private InstitutionRow require(long id, boolean lock) {
        var row = repository.institution(id, lock);
        if (row == null) {
            throw InstitutionFailure.missing();
        }
        return row;
    }

    private static void checkVersion(InstitutionRow row, Long version) {
        if (!Objects.equals(row.getVersion(), version)) {
            log.info("机构档案版本冲突，requestId={} institutionId={} expectedVersion={} currentVersion={}",
                    AuditParticipants.currentRequestId(), row.getId(), version, row.getVersion());
            throw InstitutionFailure.conflict("error.institution.version");
        }
    }

    private void update(InstitutionRow row, int actor) {
        row.setUpdatedBy(actor);
        row.setUpdatedAt(now());
        row.setVersion(row.getVersion() + 1);
        repository.update(row);
    }

    private static void write(InstitutionRow row, Save value) {
        row.setNameZh(value.nameZh());
        row.setNameEn(value.nameEn());
        row.setTypeItemId(value.typeItemId());
        row.setCountryRegionItemId(value.countryRegionItemId());
        row.setRegistrationId(value.registrationId());
        row.setShortNameZh(value.shortNameZh());
        row.setShortNameEn(value.shortNameEn());
        row.setOfficeAddress(value.officeAddress());
        row.setWebsite(value.website());
        row.setRemark(value.remark());
    }

    private Outcome<Institution> edited(Institution before, InstitutionRow row) {
        var after = view(row);
        return new Outcome<>(after, List.of(audit.change(before, after)));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE);
    }

    private static Institution view(InstitutionRow row) {
        return new Institution(row.getId(), row.getCode(), row.getNameZh(), row.getNameEn(), row.getTypeItemId(),
                row.getCountryRegionItemId(), row.getRegistrationId(), row.getShortNameZh(), row.getShortNameEn(),
                row.getOfficeAddress(), row.getWebsite(), row.getRemark(), row.getEnabled(), row.getVersion(),
                row.getCreatedBy(), row.getUpdatedBy(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private static Change view(InstitutionChangeRow row) {
        return new Change(row.getId(), row.getInstitutionId(), row.getFlowInstanceId(),
                ChangeStatus.valueOf(row.getStatus()), row.getBaseValues(), row.getResolvedAt());
    }

    private static <I, O> Page<O> mapPage(Page<I> result, Function<I, O> mapper) {
        var page = new Page<O>();
        page.setTotal(result.getTotal());
        page.setPageData(result.getPageData().stream().map(mapper).toList());
        return page;
    }
}
