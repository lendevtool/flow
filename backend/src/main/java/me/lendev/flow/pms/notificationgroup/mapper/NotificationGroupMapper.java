package me.lendev.flow.pms.notificationgroup.mapper;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.*;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupStore.*;

import java.time.LocalDateTime;
import java.util.List;

import com.mybatisflex.core.BaseMapper;
import me.lendev.flow.pms.notificationgroup.persistence.NotificationGroupEntry;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知群组持久化接口。场景和群组锁均为主键点查，列表与预览使用有界分页。
 */
public interface NotificationGroupMapper extends BaseMapper<NotificationGroupEntry> {

    String SCRIPT = "<script>\n";
    String END_SCRIPT = "</script>";
    String GROUP_TABLE = " FROM sys_notification_group g ";
    String SCENE_TABLE = " FROM sys_notification_scene s ";
    String GROUP_COLUMNS = "g.id, g.name, g.description, g.enabled, g.version, "
            + "g.created_by, g.updated_by, g.created_at, g.updated_at";
    String SCENE_COLUMNS = "s.scene_code, s.name_cn, s.name_en, s.version, s.scene_type, s.enabled,"
            + " s.in_app_title_template AS title_template, s.in_app_summary_template AS summary_template, s.mail_template_id";
    String PAGE = " LIMIT #{limit} OFFSET #{offset} ";

    String GROUP_FILTER = """
            <where>
              <if test="enabled != null">AND g.enabled = #{enabled}</if>
              <if test="keyword != null">AND LOWER(g.name) LIKE LOWER(#{keyword}) ESCAPE '!'</if>
            </where>
            """;

    String USER_FILTER = """
            FROM `user` u WHERE u.enable = 1
              <if test="keyword != null">
                AND (LOWER(u.nameCn) LIKE LOWER(#{keyword}) ESCAPE '!'
                  OR LOWER(u.nameEn) LIKE LOWER(#{keyword}) ESCAPE '!'
                  OR LOWER(u.username) LIKE LOWER(#{keyword}) ESCAPE '!')
              </if>
            """;

    // 将两类配置先转换为候选 ID，再关联启用账号。所有块共用一次去重、排序与分页。
    // MySQL JSON_TABLE 与 H2 SQL 数组各只需一个绑定参数，组织规模不受 IN 参数个数限制。
    String RECIPIENTS = """
            FROM (
              <if test="scope.hasUsers()">
                SELECT s.id AS user_id
                <choose>
                  <when test="scope.h2()">
                    FROM TABLE(id BIGINT = #{scope.userIds,typeHandler=org.apache.ibatis.type.ArrayTypeHandler}) s
                  </when>
                  <otherwise>
                    FROM JSON_TABLE(#{scope.userJson}, '$[*]' COLUMNS (id BIGINT PATH '$')) s
                  </otherwise>
                </choose>
              </if>
              <if test="scope.hasUsers() and scope.hasDepartments()">UNION ALL</if>
              <if test="scope.hasDepartments()">
                SELECT m.user_id
                FROM
                <choose>
                  <when test="scope.h2()">
                    TABLE(id BIGINT = #{scope.departmentIds,typeHandler=org.apache.ibatis.type.ArrayTypeHandler}) d
                  </when>
                  <otherwise>
                    JSON_TABLE(#{scope.departmentJson}, '$[*]' COLUMNS (id BIGINT PATH '$')) d
                  </otherwise>
                </choose>
                JOIN sys_department_member m ON m.department_id = d.id
              </if>
            ) src
            JOIN `user` u ON u.id = src.user_id AND u.enable = 1
            """;

    @Select(SCRIPT + "SELECT " + GROUP_COLUMNS + GROUP_TABLE + """
            WHERE g.id = #{id}
            <if test="lock">FOR UPDATE</if>
            """ + END_SCRIPT)
    GroupRow group(@Param("id") long id, @Param("lock") boolean lock);

    @Select(SCRIPT + "SELECT " + GROUP_COLUMNS + GROUP_TABLE + """
            WHERE g.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY g.id
            """ + END_SCRIPT)
    List<GroupRow> groups(@Param("ids") List<Long> ids);

    @Select("SELECT g.id" + GROUP_TABLE + "WHERE g.name = #{name}")
    Long groupIdForName(@Param("name") String name);

    @Select(SCRIPT + "SELECT COUNT(*)" + GROUP_TABLE + GROUP_FILTER + END_SCRIPT)
    long countGroups(@Param("keyword") String keyword, @Param("enabled") Boolean enabled);

    @Select(SCRIPT + """
            SELECT g.id, g.name, g.description, g.enabled, g.version,
              (SELECT COUNT(*) FROM sys_notification_group_user u WHERE u.group_id = g.id) AS user_count,
              (SELECT COUNT(*) FROM sys_notification_group_department d WHERE d.group_id = g.id) AS department_count,
              (SELECT COUNT(*) FROM sys_notification_scene_group s WHERE s.group_id = g.id) AS usage_count,
              g.updated_at
            """ + GROUP_TABLE + GROUP_FILTER + "ORDER BY g.id DESC" + PAGE + END_SCRIPT)
    List<GroupListRow> pageGroups(@Param("keyword") String keyword, @Param("enabled") Boolean enabled,
                                  @Param("offset") long offset, @Param("limit") int limit);

    @Select(SCRIPT + "SELECT g.id, g.name" + GROUP_TABLE + GROUP_FILTER + "ORDER BY g.id DESC" + PAGE + END_SCRIPT)
    List<GroupOption> pageOptions(@Param("keyword") String keyword, @Param("enabled") boolean enabled,
                                  @Param("offset") long offset, @Param("limit") int limit);

    @Update("""
            UPDATE sys_notification_group SET name = #{name}, description = #{description,jdbcType=VARCHAR},
              version = version + 1, updated_by = #{actor}, updated_at = #{now}
            WHERE id = #{id}
            """)
    int updateGroup(@Param("id") long id, @Param("name") String name, @Param("description") String description,
                    @Param("actor") int actor, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_notification_group SET enabled = #{enabled}, version = version + 1,
              updated_by = #{actor}, updated_at = #{now}
            WHERE id = #{id}
            """)
    int setEnabled(@Param("id") long id, @Param("enabled") boolean enabled, @Param("actor") int actor,
                   @Param("now") LocalDateTime now);

    @Select("""
            SELECT gu.user_id, u.nameCn, u.nameEn, u.username,
              CASE WHEN u.id IS NULL THEN 'MISSING' WHEN u.enable = 1 THEN 'ACTIVE' ELSE 'DISABLED' END AS status
            FROM sys_notification_group_user gu LEFT JOIN `user` u ON u.id = gu.user_id
            WHERE gu.group_id = #{id}
            ORDER BY gu.user_id
            """)
    List<GroupUser> groupUsers(@Param("id") long id);

    @Select(SCRIPT + """
            SELECT DISTINCT user_id FROM sys_notification_group_user
            WHERE group_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY user_id
            """ + END_SCRIPT)
    List<Long> users(@Param("ids") List<Long> groupIds);

    @Select(SCRIPT + """
            SELECT DISTINCT department_id, include_descendants FROM sys_notification_group_department
            WHERE group_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY department_id, include_descendants
            """ + END_SCRIPT)
    List<DepartmentSelection> departments(@Param("ids") List<Long> groupIds);

    @Delete("DELETE FROM sys_notification_group_user WHERE group_id = #{id}")
    int clearUsers(@Param("id") long id);

    @Insert(SCRIPT + """
            INSERT INTO sys_notification_group_user(group_id, user_id) VALUES
              <foreach collection="users" item="user" separator=",">(#{id}, #{user})</foreach>
            """ + END_SCRIPT)
    int addUsers(@Param("id") long id, @Param("users") List<Long> users);

    @Delete("DELETE FROM sys_notification_group_department WHERE group_id = #{id}")
    int clearDepartments(@Param("id") long id);

    @Insert(SCRIPT + """
            INSERT INTO sys_notification_group_department(group_id, department_id, include_descendants) VALUES
              <foreach collection="departments" item="d" separator=",">
                (#{id}, #{d.departmentId}, #{d.includeDescendants})
              </foreach>
            """ + END_SCRIPT)
    int addDepartments(@Param("id") long id, @Param("departments") List<DepartmentSelection> departments);

    @Select(SCRIPT + """
            SELECT id FROM `user` WHERE enable = 1 AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            """ + END_SCRIPT)
    List<Long> activeUsers(@Param("ids") List<Long> ids);

    @Select(SCRIPT + """
            SELECT id FROM sys_department WHERE enabled = TRUE AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            """ + END_SCRIPT)
    List<Long> activeDepartments(@Param("ids") List<Long> ids);

    @Select(SCRIPT + "SELECT COUNT(*) " + USER_FILTER + END_SCRIPT)
    long countUserCandidates(@Param("keyword") String keyword);

    @Select(SCRIPT + "SELECT u.id, u.nameCn, u.nameEn, u.username " + USER_FILTER + "ORDER BY u.id" + PAGE + END_SCRIPT)
    List<UserOption> pageUserCandidates(@Param("keyword") String keyword, @Param("offset") long offset,
                                        @Param("limit") int limit);

    @Select("SELECT id, parent_id, name, sort_order, enabled FROM sys_department ORDER BY sort_order, id")
    List<DepartmentRow> departmentTree();

    @Select(SCRIPT + "SELECT " + SCENE_COLUMNS + SCENE_TABLE + """
            WHERE s.scene_code = #{code}
            <if test="lock">FOR UPDATE</if>
            """ + END_SCRIPT)
    SceneRow scene(@Param("code") String sceneCode, @Param("lock") boolean lock);

    @Select("SELECT group_id FROM sys_notification_scene_group WHERE scene_code = #{code} ORDER BY group_id")
    List<Long> groupIds(@Param("code") String sceneCode);

    @Select("SELECT COUNT(*) FROM sys_notification_scene_group WHERE group_id = #{id}")
    long usageCount(@Param("id") long id);

    @Select("""
            SELECT s.scene_code, s.name_cn, s.name_en
            FROM sys_notification_scene_group b JOIN sys_notification_scene s ON s.scene_code = b.scene_code
            WHERE b.group_id = #{id} ORDER BY s.scene_code
            """ + PAGE)
    List<SceneOption> pageUsages(@Param("id") long id, @Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*)" + SCENE_TABLE)
    long countScenes();

    @Select("SELECT " + SCENE_COLUMNS + """
            , (SELECT COUNT(*) FROM sys_notification_scene_group g WHERE g.scene_code = s.scene_code) AS group_count
            """ + SCENE_TABLE + "ORDER BY s.scene_code" + PAGE)
    List<SceneListItem> pageScenes(@Param("offset") long offset, @Param("limit") int limit);

    @Delete("DELETE FROM sys_notification_scene_group WHERE scene_code = #{code}")
    int clearSceneGroups(@Param("code") String sceneCode);

    @Insert(SCRIPT + """
            INSERT INTO sys_notification_scene_group(scene_code, group_id) VALUES
              <foreach collection="groups" item="group" separator=",">(#{code}, #{group})</foreach>
            """ + END_SCRIPT)
    int addSceneGroups(@Param("code") String sceneCode, @Param("groups") List<Long> groupIds);

    @Update("""
            UPDATE sys_notification_scene SET version = version + 1, updated_by = #{actor}, updated_at = #{now}
            WHERE scene_code = #{code}
            """)
    int touchScene(@Param("code") String sceneCode, @Param("actor") int actor, @Param("now") LocalDateTime now);

    @Select(SCRIPT + "SELECT COUNT(DISTINCT u.id) " + RECIPIENTS + END_SCRIPT)
    long countRecipients(@Param("scope") RecipientScope scope);

    @Select(SCRIPT + "SELECT DISTINCT u.id, u.nameCn, u.nameEn, u.username " + RECIPIENTS + "ORDER BY u.id" + PAGE + END_SCRIPT)
    List<UserOption> pageRecipients(@Param("scope") RecipientScope scope, @Param("offset") long offset,
                                    @Param("limit") int limit);

    @Select(SCRIPT + "SELECT DISTINCT u.id " + RECIPIENTS + "ORDER BY u.id LIMIT #{limit}" + END_SCRIPT)
    List<Long> recipientIds(@Param("scope") RecipientScope scope, @Param("limit") int limit);
}
