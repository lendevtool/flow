package me.lendev.flow.institution;

import static me.lendev.flow.institution.InstitutionAccess.*;
import static me.lendev.flow.institution.InstitutionFields.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.institution.entity.InstitutionChangeRow;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.Event;
import me.lendev.flow.workbench.flow.WorkflowBusinessService.EventType;
import me.lendev.flow.workbench.flow.WorkflowBusinessSource;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 绑定现有流程模板、待办权限和机构变更单的回写范围。
 */
@Component
@RequiredArgsConstructor
class InstitutionWorkflow implements WorkflowBusinessSource {

    static final String MODULE = "institution";
    static final String BUSINESS_TYPE = "INSTITUTION_CHANGE";
    static final String TEMPLATE = "INSTITUTION_CHANGE";

    private final InstitutionRepository repository;
    private final InstitutionAccess access;
    private final InstitutionFields fields;
    private final ObjectProvider<InstitutionService> service;

    @Override
    public String module() {
        return MODULE;
    }

    @Override
    public String displayName() {
        return "机构管理";
    }

    @Override
    public boolean mayStart(Source source, String templateCode) {
        var change = find(source);
        return TEMPLATE.equals(templateCode) && change != null && change.getFlowInstanceId() == null
                && source.ownerId() != null && current(source.ownerId(), VIEW) && current(source.ownerId(), SUBMIT);
    }

    @Override
    public boolean mayRead(Source source, int viewer) {
        return current(viewer, VIEW) && find(source) != null;
    }

    @Override
    public boolean mayCreate(Source source, int assigneeUserId) {
        return find(source) != null && access.candidate(assigneeUserId);
    }

    @Override
    public String openPath(Source source, int viewer) {
        if (!current(viewer, VIEW)) {
            return null;
        }
        var change = find(source);
        return change == null ? null : "/business/institutions?id=" + change.getInstitutionId() + "&changeId=" + change.getId();
    }

    @Override
    public Set<String> inputFields(Source source, String templateCode) {
        return TEMPLATE.equals(templateCode) ? FLOW_FIELDS : Set.of();
    }

    @Override
    public Set<String> outputFields(Source source, String templateCode, EventType type) {
        return TEMPLATE.equals(templateCode) && type == EventType.COMPLETED ? APPROVABLE : Set.of();
    }

    @Override
    public Set<String> businessOnlyTemplates() {
        return Set.of(TEMPLATE);
    }

    @Override
    public Map<String, JsonNode> normalizeFields(Source source, Map<String, JsonNode> values) {
        var normalized = new LinkedHashMap<String, JsonNode>();
        values.forEach((code, value) -> normalized.put(code, canonical(value)));
        fields.validate(normalized, fields.context(false), Map.of());
        return normalized;
    }

    @Override
    public List<String> editableFields(Source source, List<String> templateFields) {
        var change = find(source);
        return change == null ? List.of() : templateFields.stream()
                .filter(code -> APPROVABLE.contains(code) && change.getBaseValues().has(code)).toList();
    }

    @Override
    public void onEvent(Event event) {
        service.getObject().apply(event);
    }

    private InstitutionChangeRow find(Source source) {
        Long id = sourceId(source);
        return id == null ? null : repository.change(id);
    }

    static Long sourceId(Source source) {
        if (source == null || !MODULE.equals(source.module()) || !BUSINESS_TYPE.equals(source.type())) {
            return null;
        }
        try {
            return Long.valueOf(source.id());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
