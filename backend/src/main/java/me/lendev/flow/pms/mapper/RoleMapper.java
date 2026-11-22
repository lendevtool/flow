package me.lendev.flow.pms.mapper;

import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;

import cn.hutool.core.text.CharSequenceUtil;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

import me.lendev.flow.pms.domain.entity.Role;

/**
 * 角色Mapper
 *
 * @author lendev
 */
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 按 ID 升序分页查询角色基本信息。
     *
     * @param page   分页参数
     * @param name   中英文名称，非空白时精确匹配
     * @param enable 启用状态，为空时查询全部状态
     * @return 当前页与总数
     */
    default Page<Role> pageRoles(Page<Role> page, String name, Boolean enable) {
        boolean hasName = CharSequenceUtil.isNotBlank(name);
        var query = QueryWrapper.create()
                .where(ROLE.NAME.eq(name, hasName).or(ROLE.NAME_EN.eq(name, hasName)))
                .and(ROLE.ENABLE.eq(enable, enable != null))
                .orderBy(ROLE.ID.asc());
        return paginate(page, query);
    }

    /**
     * 查询某用户拥有的全部角色（经由 user_roles_role 关联表）
     *
     * @param userId 用户ID
     * @return 角色列表
     */
    default List<Role> findRolesByUserId(Long userId) {
        return selectListByQuery(QueryWrapper.create().select(ROLE.ALL_COLUMNS).from(ROLE)
                .where(ROLE.ID.in(QueryWrapper.create().select(USER_ROLE.ROLE_ID).from(USER_ROLE)
                        .where(USER_ROLE.USER_ID.eq(userId, true)))));
    }
}
