package me.lendev.flow.pms.mapper;

import static me.lendev.flow.pms.domain.entity.table.PermissionTableDef.PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

import me.lendev.flow.pms.domain.entity.Permission;

/**
 * 权限Mapper
 *
 * @author lendev
 */
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 按 ID 升序分页查询资源的目录字段。
     *
     * @param page 分页参数
     * @return 当前页与总数
     */
    default Page<Permission> pageCatalog(Page<Permission> page) {
        return paginate(page, catalogColumns().orderBy(PERMISSION.ID.asc()));
    }

    /**
     * 按 ID 查询资源的目录字段。
     *
     * @param id 资源 ID
     * @return 资源目录字段，资源不存在时返回空值
     */
    default Permission findCatalogById(long id) {
        return selectOneByQuery(catalogColumns().where(PERMISSION.ID.eq(id)));
    }

    private static QueryWrapper catalogColumns() {
        return QueryWrapper.create().select(PERMISSION.ID, PERMISSION.PARENT_ID, PERMISSION.CODE, PERMISSION.NAME,
                PERMISSION.NAME_EN, PERMISSION.TYPE, PERMISSION.ENABLE, PERMISSION.ORDER).from(PERMISSION);
    }

    /**
     * 按主键锁定同一排序范围内的资源，供事务内排序和审计快照使用。
     *
     * @param parentId 父菜单 ID，空值表示只查询一级菜单
     * @return 最多返回 5001 条，用于检测排序范围是否超出请求上限
     */
    default List<Permission> lockSortableChildren(Long parentId) {
        var scope = parentId == null
                ? PERMISSION.PARENT_ID.isNull().and(PERMISSION.TYPE.eq("MENU"))
                : PERMISSION.PARENT_ID.eq(parentId).and(PERMISSION.TYPE.in("MENU", "BUTTON"));
        return selectListByQuery(QueryWrapper.create().where(scope)
                .orderBy(PERMISSION.ID.asc()).limit(5001).forUpdate());
    }

    /**
     * 查询某角色拥有的全部权限（经由 role_permissions_permission 关联表）
     *
     * @param roleId 角色ID
     * @return 权限列表
     */
    default List<Permission> findByRoleId(Long roleId) {
        return selectListByQuery(QueryWrapper.create().select(PERMISSION.ALL_COLUMNS).from(PERMISSION)
                .where(PERMISSION.ID.in(QueryWrapper.create().select(ROLE_PERMISSION.PERMISSION_ID)
                        .from(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId, true)))));
    }
}
