package me.lendev.flow.document.mapper;

import com.mybatisflex.core.BaseMapper;

import java.time.LocalDateTime;
import java.util.List;

import me.lendev.flow.document.entity.DocumentVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 文件版本表访问。
 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersionRow> {

    /**
     * 批量查询文档的最新可用版本，按已发布的日期和序号判断先后。
     */
    @Select("""
            <script>
            SELECT v.* FROM doc_document_version v JOIN doc_document d ON d.id = v.documentId
            WHERE d.deletedAt IS NULL AND v.status = 'READY' AND v.documentId IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            <choose>
                <when test="sourceModule == null">AND d.sourceModule IS NULL AND d.createdBy = #{actor}</when>
                <otherwise>
                    AND d.sourceModule = #{sourceModule} AND d.businessType = #{businessType} AND d.businessId = #{businessId}
                </otherwise>
            </choose>
            AND NOT EXISTS (
                SELECT 1 FROM doc_document_version n
                WHERE n.documentId = v.documentId AND n.status = 'READY'
                  AND (n.versionDate > v.versionDate
                       OR (n.versionDate = v.versionDate AND n.dailySequence > v.dailySequence))
            )
            </script>
            """)
    List<DocumentVersionRow> latestByDocuments(@Param("ids") List<Long> ids, @Param("actor") int actor,
                                               @Param("sourceModule") String sourceModule, @Param("businessType") String businessType,
                                               @Param("businessId") String businessId);

    /**
     * 读取已写入存储但未被文档中心锁定的文档生成版本，供补偿逐条复核。
     */
    @Select("""
            SELECT v.* FROM doc_document_version v JOIN doc_document d ON d.id = v.documentId
            WHERE d.sourceModule = 'document-generation' AND d.businessType IN ('TEMPLATE_VERSION','GENERATION')
              AND d.deletedAt IS NULL AND v.status = 'READY' AND v.updatedAt <= #{before}
              AND (d.businessVersionId IS NULL OR d.businessVersionId <> v.id)
            ORDER BY v.updatedAt, v.id LIMIT 50
            """)
    List<DocumentVersionRow> unadoptedGenerationVersions(@Param("before") LocalDateTime before);
}
