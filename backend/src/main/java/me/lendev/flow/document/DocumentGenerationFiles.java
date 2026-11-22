package me.lendev.flow.document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文档生成内部文件入口。调用方先验证生成记录或模板版本及其所有者。
 */
@Component
@RequiredArgsConstructor
public class DocumentGenerationFiles {
    /**
     * 文档中心登记模板和成品时使用的来源模块编码。
     */
    public static final String SOURCE_MODULE = "document-generation";

    /**
     * 文件归属通过固定业务类型约束，记录 ID 必须由生成模块的持久化记录提供。
     */
    public enum Target {
        TEMPLATE_VERSION,
        GENERATION
    }

    /**
     * 未固定的文件候选及其所属的业务记录，用于加锁复核。
     */
    public record Unadopted(Target target, long recordId, long documentId, long versionId) {
    }

    private final DocumentService documents;

    /**
     * 查找超过宽限且尚未固定的生成模块文件版本。
     */
    public List<Unadopted> unadopted(LocalDateTime before) {
        return documents.unadoptedGenerationVersions(before);
    }

    /**
     * 将指定业务对象的 DOCX 上传至文档中心。此方法在数据库事务之外执行。
     */
    public DocumentCenter.Version upload(Target target, long id, Long documentId, int actor, String name,
                                         DocumentCenter.Upload content) {
        var metadata = documentId == null ? new DocumentCenter.Metadata(name, null, null, Map.of()) : null;
        return documents.uploadAuthorized(owner(target, id), documentId, metadata, content, actor);
    }

    /**
     * 固定已上传的 READY 版本，可加入正在提交的业务事务。
     */
    public void bind(Target target, long id, long documentId, long versionId, int actor) {
        documents.bindAuthorized(owner(target, id), documentId, versionId, actor);
    }

    /**
     * 校验业务归属和锁定版本后同步传输文件内容。
     */
    public void download(Target target, long id, long documentId, long versionId, int actor,
                         DocumentCenter.Download writer) {
        documents.downloadAuthorized(owner(target, id), documentId, versionId, writer, actor);
    }

    /**
     * 查询指定生成记录的已上传版本，用于处理文件已写入而业务回执未提交的情况。
     */
    public List<DocumentCenter.Version> readyGeneration(long id, int actor) {
        return documents.readyGenerationVersions(owner(Target.GENERATION, id), actor);
    }

    /**
     * 清理未被业务采用的文件版本，已固定版本受到保护。
     */
    public void discard(Target target, long id, long documentId, long versionId, int actor) {
        documents.discardGenerationVersion(owner(target, id), documentId, versionId, actor);
    }

    private DocumentCenter.BusinessRecord owner(Target type, long id) {
        if (id <= 0) throw new IllegalArgumentException("文档生成业务 ID 必须为正数");
        return new DocumentCenter.BusinessRecord(SOURCE_MODULE, type.name(), Long.toString(id));
    }
}
