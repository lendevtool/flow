package me.lendev.flow.audit.query;

import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.*;
import org.springframework.stereotype.Service;

/**
 * 审计日志导出服务：生成 CSV 下载内容，并以外部操作元数据形式提供导出条件与规模的审计快照。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditExportService {

    private final AuditQueryService queries;
    private final AuditSnapshots snapshots;

    /**
     * 导出结果：CSV 字节内容及随审计事件记录的导出条件快照。
     */
    public record Download(byte[] content, List<AuditChange> auditChanges,
                           String auditSummary) implements AuditExternalMetadata {

        @Override
        public boolean equals(Object other) {
            return other instanceof Download(var otherContent, var otherChanges, var otherSummary)
                    && Arrays.equals(content, otherContent)
                    && Objects.equals(auditChanges, otherChanges)
                    && Objects.equals(auditSummary, otherSummary);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(content), auditChanges, auditSummary);
        }

        @Override
        public String toString() {
            String contentSize = content == null ? "null" : content.length + " bytes";
            return "Download[content=" + contentSize + ", auditChanges=" + auditChanges + ", auditSummary=" + auditSummary + "]";
        }
    }

    /**
     * 按请求语言生成 CSV 表头并记录导出审计。
     *
     * @param request 导出筛选条件
     * @param locale  表头使用的语言
     * @return CSV 及其审计快照
     */
    @Audited(AuditOperation.AUDIT_EXPORT)
    public Download export(AuditQueryRequest request, Locale locale) {
        var exported = queries.export(request);
        byte[] content = AuditCsv.bytes(exported.rows(), locale);
        Map<String, AuditField> fields = new LinkedHashMap<>();
        exported.criteria().forEach((key, value) -> fields.put(key, AuditField.visible(key, value)));
        fields.put("rowCount", AuditField.visible("导出条数", exported.rows().size()));
        fields.put("fileName", AuditField.visible("文件名", "operation-audit.csv"));
        AuditObject object = new AuditObject("AUDIT_EXPORT", UUID.randomUUID().toString(), null, "操作日志导出", null, null);
        log.info("导出操作日志，条数={} 语言={}", exported.rows().size(), locale.toLanguageTag());
        return new Download(content, List.of(snapshots.created(snapshots.capture(object, fields))),
                "导出操作日志：生成" + exported.rows().size() + "条摘要记录");
    }
}
