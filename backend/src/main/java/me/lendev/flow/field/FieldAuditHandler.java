package me.lendev.flow.field;

import static me.lendev.flow.field.FieldModels.*;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.pms.audit.PmsAuditSnapshots;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 在业务事务内记录字段变更，默认值只记录是否变化。
 */
@Component
@RequiredArgsConstructor
public class FieldAuditHandler implements AuditHandler {

    private final FieldService service;
    private final AuditSnapshots snapshots;
    private final ObjectMapper json;

    @Override
    public Set<AuditOperation> operations() {
        return EnumSet.of(AuditOperation.FIELD_CREATE, AuditOperation.FIELD_UPDATE, AuditOperation.FIELD_DELETE);
    }

    @Override
    public void validate(Method method) {
        switch (method.getAnnotation(Audited.class).value()) {
            case FIELD_CREATE -> PmsAuditSnapshots.bindingResult(method, Long.class, Config.class);
            case FIELD_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, Config.class);
            case FIELD_DELETE -> PmsAuditSnapshots.binding(method, Long.class);
            default -> throw new IllegalStateException("字段操作未注册");
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        if (operation == AuditOperation.FIELD_CREATE) {
            return result -> List.of(snapshots.created(capture(service.require((Long) result, true))));
        }
        long id = (Long) args[0];
        var before = capture(service.require(id, true));
        if (operation == AuditOperation.FIELD_DELETE) {
            return result -> List.of(snapshots.deleted(before));
        }
        return result -> List.of(snapshots.updated(before, capture(service.require(id, true))));
    }

    private AuditSnapshots.Capture capture(Definition definition) {
        var field = definition.config();
        ObjectNode config = json.valueToTree(field);
        config.remove(List.of("code", "name", "nameEn", "defaultValue"));
        var target = new AuditObject("FIELD_DEFINITION", Long.toString(definition.id()), field.code(), field.name(),
                null, null);
        return snapshots.capture(target, Map.of(
                "code", AuditField.visible("字段编码", field.code()),
                "name", AuditField.visible("中文名称", field.name()),
                "nameEn", AuditField.visible("英文名称", field.nameEn()),
                "config", AuditField.visible("字段配置", config),
                "defaultValue", AuditField.hidden("默认值", field.defaultValue())));
    }
}
