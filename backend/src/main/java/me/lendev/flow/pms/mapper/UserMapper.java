package me.lendev.flow.pms.mapper;

import static com.mybatisflex.core.query.QueryMethods.raw;
import static me.lendev.flow.pms.department.persistence.table.DepartmentEntryTableDef.DEPARTMENT_ENTRY;
import static me.lendev.flow.pms.domain.entity.table.ProfileTableDef.PROFILE;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.query.QueryTable;
import com.mybatisflex.core.query.QueryColumn;
import lombok.Data;
import me.lendev.flow.pms.domain.dto.UserPageDto;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.request.UserPageRequest;

/**
 * 用户Mapper
 *
 * @author lendev
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 用户和资料联表分页查询。角色归属在分页前筛选，避免同一用户拥有多个角色时出现重复记录。
     *
     * @param page    分页参数
     * @param filters 用户、角色归属和部门筛选条件
     * @return 用户分页数据
     */
    default Page<UserPageDto> pageDetail(Page<UserPageDto> page, UserPageRequest filters) {
        String username = filters.getUsername();
        String keyword = filters.getKeyword();
        Long departmentId = filters.getDepartmentId();
        var department = new QueryTable("sys_department");
        var departmentName = new QueryColumn(department, "name");
        QueryWrapper query = QueryWrapper.create()
                .select(USER.ID, USER.USERNAME, USER.NAME_CN, USER.NAME_EN, USER.DOMAIN_ACCOUNT, USER.MOBILE, USER.ENABLE, USER.CREATE_TIME, USER.UPDATE_TIME,
                        USER.DEPARTMENT_ID, USER.DEPARTMENT_VERSION, departmentName.as("departmentName"),
                        PROFILE.GENDER, PROFILE.AVATAR, PROFILE.ADDRESS, PROFILE.EMAIL, PROFILE.NICK_NAME)
                .from(USER)
                .leftJoin(PROFILE).on(USER.ID.eq(PROFILE.USER_ID, true))
                .leftJoin(department).on(USER.DEPARTMENT_ID.eq(new QueryColumn(department, "id")))
                .where(USER.USERNAME.eq(username, username != null && !username.isEmpty()))
                .and(PROFILE.GENDER.eq(filters.getGender(), filters.getGender() != null))
                .and(USER.ENABLE.eq(filters.getEnable(), filters.getEnable() != null))
                .orderBy(USER.ID.asc());
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.strip();
            query.and(USER.USERNAME.like(search).or(USER.NAME_CN.like(search)).or(USER.NAME_EN.like(search)));
        }
        if (filters.getRoleId() != null) {
            var assignedIds = QueryWrapper.create().select(USER_ROLE.USER_ID).from(USER_ROLE)
                    .where(USER_ROLE.ROLE_ID.eq(filters.getRoleId()));
            query.and(filters.isRoleAssigned() ? USER.ID.in(assignedIds) : USER.ID.notIn(assignedIds));
        }
        var membership = new QueryTable("sys_department_member");
        var memberIds = QueryWrapper.create().select(new QueryColumn(membership, "user_id")).from(membership);
        if (filters.isUnassigned()) {
            query.and(USER.ID.notIn(memberIds));
        } else if (departmentId != null && filters.getDepartmentScope() == UserPageRequest.DepartmentScope.ANY) {
            query.and(USER.ID.in(memberIds.where(new QueryColumn(membership, "department_id").eq(departmentId))));
        } else if (departmentId != null) {
            query.and(USER.DEPARTMENT_ID.eq(departmentId));
        }
        return paginateAs(page, query, UserPageDto.class);
    }

    /**
     * 按姓名、启用状态或主部门筛选工作目录，以用户 ID 稳定分页。
     */
    default Page<DirectoryRow> pageDirectory(int pageNo, int pageSize, String keyword, Boolean enabled,
                                             Long departmentId) {
        var query = directory().where(USER.ENABLE.eq(enabled, enabled != null))
                .and(USER.DEPARTMENT_ID.eq(departmentId, departmentId != null)).orderBy(USER.ID.asc());
        if (keyword != null && !keyword.isBlank()) {
            var pattern = "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.and(raw("""
                    (LOWER(`user`.nameCn) LIKE LOWER(?) ESCAPE '!'
                        OR LOWER(`user`.nameEn) LIKE LOWER(?) ESCAPE '!')
                    """, pattern, pattern));
        }
        return paginateAs(pageNo, pageSize, query, DirectoryRow.class);
    }

    /**
     * 查询指定用户的工作目录字段，用户不存在时返回空值。
     */
    default DirectoryRow findDirectory(long id) {
        return selectOneByQueryAs(directory().where(USER.ID.eq(id)), DirectoryRow.class);
    }

    private static QueryWrapper directory() {
        return QueryWrapper.create().select(USER.ID, USER.NAME_CN, USER.NAME_EN, USER.ENABLE.as("enabled"),
                        USER.DEPARTMENT_ID, DEPARTMENT_ENTRY.NAME.as("departmentName"))
                .from(USER).leftJoin(DEPARTMENT_ENTRY).on(USER.DEPARTMENT_ID.eq(DEPARTMENT_ENTRY.ID));
    }

    /**
     * 用户工作目录的数据投影。
     */
    @Data
    class DirectoryRow {
        private long id;
        private String nameCn;
        private String nameEn;
        private boolean enabled;
        private Long departmentId;
        private String departmentName;
    }
}
