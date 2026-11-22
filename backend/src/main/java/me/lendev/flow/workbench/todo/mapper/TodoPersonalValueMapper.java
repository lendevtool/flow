package me.lendev.flow.workbench.todo.mapper;

import static com.mybatisflex.core.query.QueryMethods.distinct;
import static me.lendev.flow.workbench.todo.entity.table.TodoPersonalValueTableDef.TODO_PERSONAL_VALUE;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.util.Collection;
import java.util.List;

import me.lendev.flow.workbench.todo.entity.TodoPersonalValue;

/**
 * 按用户与槽位读写待办的个人维度标记。
 */
public interface TodoPersonalValueMapper extends BaseMapper<TodoPersonalValue> {

    /**
     * 批量查询用户在指定待办上的标记，空 ID 集合直接返回空列表。
     */
    default List<TodoPersonalValue> values(int userId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return selectListByQuery(QueryWrapper.create().where(TODO_PERSONAL_VALUE.USER_ID.eq(userId))
                .and(TODO_PERSONAL_VALUE.TODO_ID.in(ids)));
    }

    /**
     * 查询槽位上仍被标记引用的去重选项键，用于配置变更时的引用校验。
     */
    default List<String> referencedValues(int userId, int slotNo) {
        return selectObjectListByQueryAs(QueryWrapper.create().select(distinct(TODO_PERSONAL_VALUE.OPTION_KEY))
                .from(TODO_PERSONAL_VALUE).where(TODO_PERSONAL_VALUE.USER_ID.eq(userId))
                .and(TODO_PERSONAL_VALUE.SLOT_NO.eq(slotNo)), String.class);
    }

    /**
     * 清除指定待办槽位的标记。
     */
    default void deleteTag(int userId, long todoId, int slotNo) {
        deleteByCondition(tag(userId, todoId, slotNo));
    }

    /**
     * 按用户、待办与槽位覆盖标记值。
     */
    default void updateTag(TodoPersonalValue row) {
        UpdateChain.of(this).set(TODO_PERSONAL_VALUE.OPTION_KEY, row.getOptionKey())
                .set(TODO_PERSONAL_VALUE.UPDATED_AT, row.getUpdatedAt())
                .where(tag(row.getUserId(), row.getTodoId(), row.getSlotNo())).update();
    }

    private static QueryCondition tag(int userId, long todoId, int slotNo) {
        return TODO_PERSONAL_VALUE.USER_ID.eq(userId).and(TODO_PERSONAL_VALUE.TODO_ID.eq(todoId))
                .and(TODO_PERSONAL_VALUE.SLOT_NO.eq(slotNo));
    }
}
