package me.lendev.flow.workbench.todo.mapper;

import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.workbench.todo.entity.table.TodoPersonalDimensionTableDef.TODO_PERSONAL_DIMENSION;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.util.List;

import me.lendev.flow.workbench.todo.entity.TodoPersonalDimension;

/**
 * 按用户隔离个人维度定义，新增维度前锁定用户行。
 */
public interface TodoPersonalDimensionMapper extends BaseMapper<TodoPersonalDimension> {

    /**
     * 按槽位号升序查询用户的全部维度配置。
     */
    default List<TodoPersonalDimension> dimensions(int userId) {
        return selectListByQuery(QueryWrapper.create().where(TODO_PERSONAL_DIMENSION.USER_ID.eq(userId))
                .orderBy(TODO_PERSONAL_DIMENSION.SLOT_NO.asc()));
    }

    /**
     * 以 FOR UPDATE 锁定用户行，串行化同一用户的维度新建。
     */
    default Integer lockUser(int userId) {
        return selectObjectByQueryAs(QueryWrapper.create().select(USER.ID).from(USER)
                .where(USER.ID.eq(userId)).forUpdate(), Integer.class);
    }

    /**
     * 以 FOR UPDATE 锁定指定槽位配置，不存在时返回 null。
     */
    default TodoPersonalDimension lockDimension(int userId, int slotNo) {
        return selectOneByQuery(QueryWrapper.create().where(TODO_PERSONAL_DIMENSION.USER_ID.eq(userId))
                .and(TODO_PERSONAL_DIMENSION.SLOT_NO.eq(slotNo)).forUpdate());
    }

    /**
     * 按用户与槽位覆盖配置内容，调用方须已锁定槽位行并校验版本。
     */
    default void updateDimension(TodoPersonalDimension row) {
        UpdateChain.of(this).set(TODO_PERSONAL_DIMENSION.NAME, row.getName())
                .set(TODO_PERSONAL_DIMENSION.OPTIONS_JSON, row.getOptionsJson())
                .set(TODO_PERSONAL_DIMENSION.ENABLE, row.getEnable())
                .set(TODO_PERSONAL_DIMENSION.VERSION, row.getVersion())
                .set(TODO_PERSONAL_DIMENSION.UPDATED_AT, row.getUpdatedAt())
                .where(TODO_PERSONAL_DIMENSION.USER_ID.eq(row.getUserId()))
                .and(TODO_PERSONAL_DIMENSION.SLOT_NO.eq(row.getSlotNo()))
                .update();
    }
}
