package me.lendev.flow.pms.mapper;

import static com.mybatisflex.core.query.QueryMethods.coalesce;
import static com.mybatisflex.core.query.QueryMethods.exists;
import static com.mybatisflex.core.query.QueryMethods.notExists;
import static com.mybatisflex.core.query.QueryMethods.raw;
import static me.lendev.flow.pms.department.persistence.table.DepartmentEntryTableDef.DEPARTMENT_ENTRY;
import static me.lendev.flow.pms.department.persistence.table.DepartmentMembershipTableDef.DEPARTMENT_MEMBERSHIP;
import static me.lendev.flow.pms.domain.entity.table.ProfileTableDef.PROFILE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;

import java.util.List;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryTable;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.Data;
import me.lendev.flow.pms.department.persistence.DepartmentEntry;

/**
 * 部门、负责人及成员归属的持久化查询。
 */
public interface DepartmentMapper extends BaseMapper<DepartmentEntry> {

    /**
     * 锁定组织写入的单行互斥记录。
     */
    default Integer lockWrites() {
        var lockTable = new QueryTable("sys_department_write_lock");
        var id = new QueryColumn(lockTable, "id");
        return selectObjectByQueryAs(QueryWrapper.create().select(id).from(lockTable)
                .where(id.eq(1)).forUpdate(), Integer.class);
    }

    default List<DepartmentEntry> list() {
        return selectListByQuery(departments().orderBy(DEPARTMENT_ENTRY.SORT_ORDER.asc(), DEPARTMENT_ENTRY.ID.asc()));
    }

    default List<DepartmentEntry> ledBy(Long userId) {
        return selectListByQuery(departments().where(DEPARTMENT_ENTRY.LEADER_USER_ID.eq(userId, true))
                .orderBy(DEPARTMENT_ENTRY.ID.asc()));
    }

    default List<MemberRow> members(List<Long> ids) {
        return ids.isEmpty() ? List.of() : selectListByQueryAs(members().where(USER.ID.in(ids)), MemberRow.class);
    }

    default List<MembershipRow> memberships(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        var query = QueryWrapper.create()
                .select(DEPARTMENT_MEMBERSHIP.USER_ID.as("userId"), DEPARTMENT_ENTRY.ID.as("departmentId"),
                        DEPARTMENT_ENTRY.NAME)
                .from(DEPARTMENT_MEMBERSHIP).join(DEPARTMENT_ENTRY)
                .on(DEPARTMENT_ENTRY.ID.eq(DEPARTMENT_MEMBERSHIP.DEPARTMENT_ID))
                .where(DEPARTMENT_MEMBERSHIP.USER_ID.in(userIds))
                .orderBy(DEPARTMENT_MEMBERSHIP.USER_ID.asc(), DEPARTMENT_ENTRY.ID.asc());
        return selectListByQueryAs(query, MembershipRow.class);
    }

    default Page<MemberRow> pageMembers(boolean unassigned, List<Long> departmentIds, Boolean enabled,
                                        String keyword, int pageNo, int size) {
        var query = members().select(PROFILE.AVATAR).leftJoin(PROFILE).on(USER.ID.eq(PROFILE.USER_ID, true))
                .where(USER.ENABLE.eq(enabled, enabled != null));
        var assignment = QueryWrapper.create().select(DEPARTMENT_MEMBERSHIP.USER_ID).from(DEPARTMENT_MEMBERSHIP)
                .where(DEPARTMENT_MEMBERSHIP.USER_ID.eq(USER.ID));
        if (unassigned) {
            query.and(notExists(assignment));
        } else if (!departmentIds.isEmpty()) {
            query.and(exists(assignment.and(DEPARTMENT_MEMBERSHIP.DEPARTMENT_ID.in(departmentIds))));
        }
        if (keyword != null) {
            query.and(raw("""
                    (LOWER(`user`.username) LIKE LOWER(?) ESCAPE '!'
                        OR LOWER(`user`.nameCn) LIKE LOWER(?) ESCAPE '!'
                        OR LOWER(`user`.nameEn) LIKE LOWER(?) ESCAPE '!')
                    """, keyword, keyword, keyword));
        }
        return paginateAs(pageNo, size, query, MemberRow.class);
    }

    private static QueryWrapper departments() {
        return QueryWrapper.create()
                .select(DEPARTMENT_ENTRY.DEFAULT_COLUMNS, coalesce(USER.NAME_CN, coalesce(USER.NAME_EN, USER.USERNAME))
                        .as("leaderName"))
                .from(DEPARTMENT_ENTRY).leftJoin(USER).on(DEPARTMENT_ENTRY.LEADER_USER_ID.eq(USER.ID));
    }

    private static QueryWrapper members() {
        return QueryWrapper.create().select(USER.ID, USER.USERNAME, USER.NAME_CN, USER.NAME_EN, USER.DEPARTMENT_ID,
                        DEPARTMENT_ENTRY.NAME.as("departmentName"), USER.ENABLE, USER.DEPARTMENT_VERSION)
                .from(USER).leftJoin(DEPARTMENT_ENTRY).on(USER.DEPARTMENT_ID.eq(DEPARTMENT_ENTRY.ID))
                .orderBy(USER.ID.asc());
    }

    @Data
    class MemberRow {

        private Long id;
        private String username;
        private String avatar;
        private String nameCn;
        private String nameEn;
        private Long departmentId;
        private String departmentName;
        private boolean enable;
        private long departmentVersion;
    }

    @Data
    class MembershipRow {

        private long userId;
        private long departmentId;
        private String name;
    }
}
