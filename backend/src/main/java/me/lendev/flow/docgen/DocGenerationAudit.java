package me.lendev.flow.docgen;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditContext;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditRecorder;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditObject;
import org.springframework.stereotype.Component;

/**
 * 只登记状态与对象标识，避免把模板配置、输入或文件内容写入操作日志。
 */
@Component
@RequiredArgsConstructor
class DocGenerationAudit {
    private final AuditContext context;
    private final AuditRecorder recorder;
    private final AuditSnapshots snapshots;

    void template(AuditOperation operation, long id, Map<String, AuditField> fields) {
        recordSuccess(operation, new AuditObject("DOCGEN_TEMPLATE", Long.toString(id), null,
                "文档生成模板", null, null), fields);
    }

    void version(AuditOperation operation, long id, long templateId, Map<String, AuditField> fields) {
        recordSuccess(operation, new AuditObject("DOCGEN_VERSION", Long.toString(id), null,
                "文档生成模板版本", "DOCGEN_TEMPLATE", Long.toString(templateId)), fields);
    }

    void generation(AuditOperation operation, long id, Map<String, AuditField> fields) {
        recordSuccess(operation, new AuditObject("DOCGEN_RECORD", Long.toString(id), null,
                "文档生成记录", null, null), fields);
    }

    private void recordSuccess(AuditOperation operation, AuditObject object, Map<String, AuditField> fields) {
        var scope = context.start(operation, new Object[0]);
        recorder.success(scope, operation, List.of(snapshots.created(snapshots.capture(object, fields))));
    }
}
