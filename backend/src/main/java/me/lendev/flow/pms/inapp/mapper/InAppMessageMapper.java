package me.lendev.flow.pms.inapp.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 个人消息按收件用户查询，业务通知按稳定来源键查重。
 */
public interface InAppMessageMapper {

    @Insert("""
            INSERT INTO sys_in_app_message(recipient_user_id,source_module,source_key,message_type,title,summary,target_type,target_id,created_at)
            VALUES(#{userId},#{module},#{sourceKey},#{type},#{title},#{summary,jdbcType=VARCHAR},#{targetType,jdbcType=VARCHAR},#{targetId,jdbcType=BIGINT},#{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(NewMessage message);

    @Select("""
            SELECT id FROM sys_in_app_message
            WHERE recipient_user_id=#{userId} AND source_module=#{module} AND source_key=#{sourceKey}
            FOR UPDATE
            """)
    Long existing(@Param("userId") int userId, @Param("module") String module, @Param("sourceKey") String sourceKey);

    @Select("""
            <script>
            SELECT id,message_type,title,summary,target_type,target_id,created_at,read_at
            FROM sys_in_app_message WHERE recipient_user_id=#{userId}
            <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            <if test="unread != null and unread">AND read_at IS NULL</if>
            <if test="unread != null and !unread">AND read_at IS NOT NULL</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<Row> page(@Param("userId") int userId, @Param("unread") Boolean unread,
                   @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM sys_in_app_message WHERE recipient_user_id=#{userId} AND read_at IS NULL")
    long unread(@Param("userId") int userId);

    @Update("UPDATE sys_in_app_message SET read_at=#{time} WHERE id=#{id} AND recipient_user_id=#{userId} AND read_at IS NULL")
    int read(@Param("id") long id, @Param("userId") int userId, @Param("time") LocalDateTime time);

    @Select("SELECT id FROM sys_in_app_message WHERE id=#{id} AND recipient_user_id=#{userId}")
    Long owned(@Param("id") long id, @Param("userId") int userId);

    record Row(long id, String messageType, String title, String summary, String targetType, Long targetId,
               LocalDateTime createdAt, LocalDateTime readAt) {
    }

    @lombok.Getter
    @lombok.RequiredArgsConstructor
    class NewMessage {
        @lombok.Setter
        private Long id;
        private final int userId;
        private final String module;
        private final String sourceKey;
        private final String type;
        private final String title;
        private final String summary;
        private final String targetType;
        private final Long targetId;
        private final LocalDateTime createdAt;
    }
}
