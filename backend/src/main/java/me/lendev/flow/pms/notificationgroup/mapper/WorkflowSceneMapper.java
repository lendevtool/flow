package me.lendev.flow.pms.notificationgroup.mapper;

import java.time.LocalDateTime;
import java.util.List;

import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.Scene;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 维护流程场景的配置和影响范围。
 */
public interface WorkflowSceneMapper {

    String COLUMNS = "scene_code,name_cn,name_en,scene_type AS type,enabled,version,activation_version,"
            + "in_app_title_template AS title_template,in_app_summary_template AS summary_template,mail_template_id";

    @Select("<script>SELECT " + COLUMNS + " FROM sys_notification_scene WHERE scene_code=#{code} "
            + "<if test='lock'>FOR UPDATE</if></script>")
    Scene scene(@Param("code") String code, @Param("lock") boolean lock);

    @Select("SELECT " + COLUMNS + " FROM sys_notification_scene WHERE scene_type='WORKFLOW_EXTRA' AND enabled=TRUE ORDER BY scene_code")
    List<Scene> enabledExtras();

    @Select("SELECT COUNT(*) FROM sys_notification_scene_group WHERE scene_code=#{code}")
    long groupCount(@Param("code") String code);

    @Select("""
            SELECT COUNT(DISTINCT d.id) FROM wf_definition_notification_ref r
            JOIN wf_definition d ON d.id=r.definition_id WHERE r.scene_code=#{code} AND d.status='PUBLISHED'
            """)
    long publishedDefinitionCount(@Param("code") String code);

    @Select("""
            SELECT COUNT(DISTINCT i.id) FROM wf_definition_notification_ref r
            JOIN wf_instance i ON i.definitionId=r.definition_id WHERE r.scene_code=#{code} AND i.status='RUNNING'
            """)
    long runningInstanceCount(@Param("code") String code);

    String MAIL_TEMPLATE = """
            SELECT t.id,t.code,t.name,(t.enable = TRUE AND a.enable = TRUE) AS enable,t.subjectTemplate,t.bodyTemplate
            FROM sys_mail_template t JOIN sys_mail_account a ON a.id=t.accountId
            """;

    @Select(MAIL_TEMPLATE + "WHERE t.id=#{id}")
    MailTemplate mailTemplate(@Param("id") long id);

    @Select(MAIL_TEMPLATE + "ORDER BY t.name,t.id")
    List<MailTemplate> mailTemplates();

    @Insert("""
            INSERT INTO sys_notification_scene(scene_code,name_cn,name_en,scene_type,enabled,in_app_title_template,
                in_app_summary_template,mail_template_id,updated_by,updated_at)
            VALUES (#{row.sceneCode},#{row.nameCn},#{row.nameEn,jdbcType=VARCHAR},#{row.type},#{row.enabled},
                #{row.titleTemplate,jdbcType=VARCHAR},#{row.summaryTemplate,jdbcType=VARCHAR},#{row.mailTemplateId,jdbcType=BIGINT},#{actor},#{now})
            """)
    int insert(@Param("row") Scene row, @Param("actor") int actor, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_notification_scene SET name_cn=#{row.nameCn},name_en=#{row.nameEn,jdbcType=VARCHAR},enabled=#{row.enabled},
                in_app_title_template=#{row.titleTemplate,jdbcType=VARCHAR},in_app_summary_template=#{row.summaryTemplate,jdbcType=VARCHAR},
                mail_template_id=#{row.mailTemplateId,jdbcType=BIGINT},activation_version=#{row.activationVersion},
                version=#{row.version},updated_by=#{actor},updated_at=#{now}
            WHERE scene_code=#{row.sceneCode} AND version=#{previousVersion}
            """)
    int update(@Param("row") Scene row, @Param("previousVersion") long previousVersion,
               @Param("actor") int actor, @Param("now") LocalDateTime now);

    record MailTemplate(long id, String code, String name, boolean enable, String subjectTemplate,
                        String bodyTemplate) {
    }
}
