package me.lendev.flow.pms.mapper;

import static me.lendev.flow.pms.department.persistence.table.DepartmentMembershipTableDef.DEPARTMENT_MEMBERSHIP;

import java.util.Collection;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import me.lendev.flow.pms.department.persistence.DepartmentMembership;

/**
 * 维护用户与部门的归属关系。
 */
public interface DepartmentMembershipMapper extends BaseMapper<DepartmentMembership> {

    default boolean hasMembers(Long departmentId) {
        return selectObjectByQueryAs(QueryWrapper.create().select(DEPARTMENT_MEMBERSHIP.USER_ID)
                .where(DEPARTMENT_MEMBERSHIP.DEPARTMENT_ID.eq(departmentId, true)).limit(1), Long.class) != null;
    }

    default void add(Long userId, Long departmentId) {
        var membership = new DepartmentMembership();
        membership.setUserId(userId);
        membership.setDepartmentId(departmentId);
        insertWithPk(membership);
    }

    default void remove(Long userId, Collection<Long> departmentIds) {
        if (!departmentIds.isEmpty()) {
            deleteByQuery(QueryWrapper.create().where(DEPARTMENT_MEMBERSHIP.USER_ID.eq(userId, true))
                    .and(DEPARTMENT_MEMBERSHIP.DEPARTMENT_ID.in(departmentIds)));
        }
    }

    default void clear(Long userId) {
        deleteByQuery(QueryWrapper.create().where(DEPARTMENT_MEMBERSHIP.USER_ID.eq(userId, true)));
    }
}
