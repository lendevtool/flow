package me.lendev.flow.institution;

import static me.lendev.flow.audit.AuditOperation.INSTITUTION_APPLY;
import static me.lendev.flow.audit.AuditOperation.INSTITUTION_CREATE;
import static me.lendev.flow.audit.AuditOperation.INSTITUTION_EDIT;
import static me.lendev.flow.audit.AuditOperation.INSTITUTION_STATUS;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.institution.InstitutionModels.Institution;
import org.springframework.stereotype.Component;

/**
 * 机构档案业务字段的审计快照及审批流程引用。
 */
@Component
@RequiredArgsConstructor
class InstitutionAudit implements AuditHandler {

    private static final String INSTITUTION = "INSTITUTION";

    private final AuditSnapshots snapshots;

    record Outcome<T>(T value, List<AuditChange> details) {

    }

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(INSTITUTION_CREATE, INSTITUTION_EDIT, INSTITUTION_STATUS, INSTITUTION_APPLY);
    }

    @Override
    public void validate(Method method) {
        if (method.getReturnType() != Outcome.class) {
            throw new IllegalStateException("机构审计方法须返回 Outcome: " + method.getName());
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return new Plan() {
            @Override
            public boolean recordSuccess(Object result) {
                return !after(result).isEmpty();
            }

            @Override
            public List<AuditChange> after(Object result) {
                return ((Outcome<?>) result).details();
            }
        };
    }

    AuditChange change(Institution before, Institution after) {
        var captured = capture(after);
        return before == null ? snapshots.created(captured) : snapshots.updated(capture(before), captured);
    }

    AuditChange workflow(long changeId, long flowInstanceId, long institutionId) {
        return snapshots.reference(new AuditObject("WORKFLOW_INSTANCE", Long.toString(flowInstanceId), null,
                "机构资料变更申请 " + changeId, INSTITUTION, Long.toString(institutionId)));
    }

    private AuditSnapshots.Capture capture(Institution institution) {
        var object = new AuditObject(INSTITUTION, Long.toString(institution.id()), institution.code(),
                institution.nameZh(), null, null);
        return snapshots.capture(object, Map.ofEntries(
                Map.entry("code", AuditField.visible("机构编码", institution.code())),
                Map.entry("nameZh", AuditField.visible("中文全称", institution.nameZh())),
                Map.entry("nameEn", AuditField.visible("英文全称", institution.nameEn())),
                Map.entry("typeItemId", AuditField.visible("机构类型", institution.typeItemId())),
                Map.entry("countryRegionItemId", AuditField.visible("注册国家或地区", institution.countryRegionItemId())),
                Map.entry("registrationId", AuditField.visible("注册识别号", institution.registrationId())),
                Map.entry("shortNameZh", AuditField.visible("中文简称", institution.shortNameZh())),
                Map.entry("shortNameEn", AuditField.visible("英文简称", institution.shortNameEn())),
                Map.entry("officeAddress", AuditField.visible("办公地址", institution.officeAddress())),
                Map.entry("website", AuditField.visible("官网", institution.website())),
                Map.entry("remark", AuditField.visible("备注", institution.remark())),
                Map.entry("enabled", AuditField.visible("启停状态", institution.enabled()))));
    }
}
