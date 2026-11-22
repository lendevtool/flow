package me.lendev.flow.workbench.flow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 按模板版本持久化节点动作引用。写入方须先锁定模板行。
 */
public interface WorkflowNotificationRefMapper {

    /**
     * 一个模板版本内的节点动作引用。
     */
    record Ref(long definitionId, String nodeKey, String triggerType, String sceneCode) {
    }

    /**
     * 一次读取多个模板版本的引用，调用方须传入非空版本列表。
     */
    @Select("""
            <script>
            SELECT definition_id AS definitionId, node_key AS nodeKey,
                   trigger_type AS triggerType, scene_code AS sceneCode
            FROM wf_definition_notification_ref
            WHERE definition_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            <if test="lock">FOR UPDATE</if>
            </script>
            """)
    List<Ref> findByDefinitionIds(@Param("ids") List<Long> ids, @Param("lock") boolean lock);

    /**
     * 删除要覆盖的草稿版本引用。
     */
    @Delete("DELETE FROM wf_definition_notification_ref WHERE definition_id = #{id}")
    void deleteByDefinitionId(@Param("id") long id);

    /**
     * 批量写入草稿引用，调用方须传入非空列表。
     */
    @Insert("""
            <script>
            INSERT INTO wf_definition_notification_ref (definition_id, node_key, trigger_type, scene_code)
            VALUES
            <foreach collection="refs" item="ref" separator=",">
                (#{ref.definitionId}, #{ref.nodeKey}, #{ref.triggerType}, #{ref.sceneCode})
            </foreach>
            </script>
            """)
    void insertRefs(@Param("refs") List<Ref> refs);
}
