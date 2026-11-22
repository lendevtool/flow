package me.lendev.flow.policy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditObject;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 将登记的策略属性映射为独立的审计字段。
 */
@Component
@RequiredArgsConstructor
public class PolicyAuditHandler implements AuditHandler {

    private final PolicyCatalog catalog;
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.POLICY_UPDATE, AuditOperation.POLICY_RESET);
    }

    @Override
    public void validate(Method method) {
        Class<?>[] parameters = method.getAnnotation(Audited.class).value() == AuditOperation.POLICY_UPDATE
                ? new Class<?>[]{String.class, long.class, JsonNode.class} : new Class<?>[]{String.class, long.class};
        if (!Arrays.equals(parameters, method.getParameterTypes()) || method.getReturnType() != PolicyService.View.class) {
            throw new IllegalStateException("系统策略审计方法签名不一致: " + method.getName());
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        var definition = catalog.require((String) args[0]);
        var before = capture(definition, catalog.read(definition, true));
        return result -> List.of(snapshots.updated(before, capture(definition, catalog.read(definition, true))));
    }

    private AuditSnapshots.Capture capture(PolicyDefinition definition, PolicyRepository.Stored row) {
        var fields = new LinkedHashMap<String, AuditField>();
        definition.fieldLabels().forEach((path, label) -> {
            var value = row.config();
            for (String segment : path.split("\\.")) {
                value = value.path(segment);
            }
            fields.put(path, value.isMissingNode() ? AuditField.missing(label, true) : AuditField.visible(label, value));
        });
        return snapshots.capture(new AuditObject("SYSTEM_POLICY", definition.code(), definition.code(),
                definition.label(), null, null), fields);
    }
}
