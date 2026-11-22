package me.lendev.flow.document;

import static me.lendev.flow.audit.AuditOperation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditExternalMetadata;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.document.DocumentCenter.BusinessRecord;
import me.lendev.flow.document.entity.DocumentRow;
import me.lendev.flow.document.entity.DocumentVersionRow;
import org.springframework.stereotype.Component;

/**
 * 文档业务字段的审计快照和外部传输结果。
 */
@Component
@RequiredArgsConstructor
class DocumentAuditDetails implements AuditHandler {

    private final AuditSnapshots snapshots;

    record Change<T>(T result, List<AuditChange> details) {

    }

    record Transfer(List<AuditChange> auditChanges, String auditSummary) implements AuditExternalMetadata {

    }

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(DOCUMENT_PUBLISH, DOCUMENT_EDIT, DOCUMENT_BIND, DOCUMENT_DETACH, DOCUMENT_DELETE);
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return new Plan() {
            @Override
            public boolean recordSuccess(Object result) {
                return !((Change<?>) result).details().isEmpty();
            }

            @Override
            public List<AuditChange> after(Object result) {
                return ((Change<?>) result).details();
            }
        };
    }

    AuditChange document(DocumentRow before, DocumentRow after) {
        var capture = documentSnapshot(after);
        return before == null ? snapshots.created(capture) : snapshots.updated(documentSnapshot(before), capture);
    }

    AuditChange published(DocumentVersionRow row, BusinessRecord business) {
        return snapshots.created(versionSnapshot(row, business));
    }

    Transfer transferred(DocumentVersionRow row, BusinessRecord business, String summary) {
        var file = snapshots.reference(versionObject(row, versionNo(row)));
        if (business == null) {
            return new Transfer(List.of(file), summary);
        }
        var target = new AuditObject("DOCUMENT_BUSINESS_RECORD", business.id(), business.type(), business.id(),
                "DOCUMENT_BUSINESS_MODULE", business.module());
        return new Transfer(List.of(file, snapshots.reference(target)), summary);
    }

    Transfer uploaded(DocumentVersionRow row, BusinessRecord business) {
        return new Transfer(List.of(snapshots.created(versionSnapshot(row, business))), "完成 SFTP 文件上传");
    }

    private AuditSnapshots.Capture documentSnapshot(DocumentRow row) {
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("name", AuditField.visible("名称", row.getName()));
        fields.put("departmentId", AuditField.visible("部门", row.getDepartmentId()));
        fields.put("productId", AuditField.visible("产品", row.getProductId()));
        var dimensions = DocumentRepository.dimensions(row);
        for (int slot = 1; slot <= 6; slot++) {
            fields.put("dimension" + slot + "Value", AuditField.visible("维度 " + slot, dimensions.get(slot)));
        }
        fields.put("sourceModule", AuditField.visible("业务模块", row.getSourceModule()));
        fields.put("businessType", AuditField.visible("业务类型", row.getBusinessType()));
        fields.put("businessId", AuditField.visible("业务记录 ID", row.getBusinessId()));
        fields.put("businessVersionId", AuditField.visible("锁定版本 ID", row.getBusinessVersionId()));
        fields.put("deletedAt", AuditField.visible("删除时间", row.getDeletedAt()));
        return snapshots.capture(new AuditObject("DOCUMENT", row.getId().toString(), null, row.getName(), null, null), fields);
    }

    private AuditSnapshots.Capture versionSnapshot(DocumentVersionRow row, BusinessRecord business) {
        String versionNo = versionNo(row);
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("versionNo", AuditField.visible("版本号", versionNo));
        fields.put("originalFileName", AuditField.visible("文件名", row.getOriginalFileName()));
        fields.put("sizeBytes", AuditField.visible("字节数", row.getSizeBytes()));
        if (business != null) {
            fields.put("sourceModule", AuditField.visible("业务模块", business.module()));
            fields.put("businessType", AuditField.visible("业务类型", business.type()));
            fields.put("businessId", AuditField.visible("业务记录 ID", business.id()));
        }
        return snapshots.capture(versionObject(row, versionNo), fields);
    }

    private static String versionNo(DocumentVersionRow row) {
        return row.getVersionDate() == null ? null : DocumentNames.versionNo(row.getVersionDate(), row.getDailySequence());
    }

    private static AuditObject versionObject(DocumentVersionRow row, String versionNo) {
        return new AuditObject("DOCUMENT_VERSION", row.getId().toString(), versionNo, row.getOriginalFileName(),
                "DOCUMENT", row.getDocumentId().toString());
    }
}
