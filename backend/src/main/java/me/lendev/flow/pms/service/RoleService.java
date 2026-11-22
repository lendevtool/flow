package me.lendev.flow.pms.service;

import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.RoleCatalogDto;
import me.lendev.flow.pms.domain.dto.RolePageDto;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.request.AddRolePermissionsRequest;
import me.lendev.flow.pms.domain.request.AddRoleUsersRequest;
import me.lendev.flow.pms.domain.request.CreateRoleRequest;
import me.lendev.flow.pms.domain.request.RemoveRoleUsersRequest;
import me.lendev.flow.pms.domain.request.RolePageRequest;
import me.lendev.flow.pms.domain.request.UpdateRoleRequest;
import cn.hutool.core.lang.tree.Tree;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 角色服务
 *
 * @author lendev
 */
public interface RoleService extends IService<Role> {

    /**
     * 根据用户id查询角色
     *
     * @param userId 用户id
     * @return 用户角色列表
     */
    List<Role> findRolesByUserId(Long userId);

    /**
     * 根据角色编码获取权限树
     *
     * @param roleCode 角色编码
     * @return 权限树
     */
    List<Tree<Long>> findRolePermissionsTree(String roleCode);

    /**
     * 根据角色编码获取角色
     *
     * @param roleCode 角色编码
     * @return 角色
     */
    Role findByCode(String roleCode);


    /**
     * 创建角色
     *
     * @param request 创建角色请求
     */
    void createRole(CreateRoleRequest request);

    /**
     * 分页查询
     *
     * @param request 分页查询请求
     * @return 角色分页数据
     */
    Page<RolePageDto> queryPage(RolePageRequest request);

    /**
     * 分页查询角色目录。
     *
     * @param name     可选的中英文名称
     * @param enable   可选的启用状态
     * @param pageNo   从 1 开始的页码
     * @param pageSize 已校验的每页条数
     * @return 角色目录当前页与总数
     */
    Page<RoleCatalogDto> catalogPage(String name, Boolean enable, int pageNo, int pageSize);

    /**
     * 根据 ID 读取角色目录项。
     *
     * @param id 角色 ID
     * @return 角色目录项
     * @throws me.lendev.flow.common.exception.ResourceNotFoundException 角色不存在
     */
    RoleCatalogDto catalogEntry(long id);

    /**
     * 查询角色权限
     *
     * @param id 角色id
     * @return 角色权限
     */
    List<PermissionDto> findRolePermissions(Long id);

    /**
     * 更新角色，当角色标识是管理员时，不给修改
     *
     * @param id      角色id
     * @param request 更新角色请求
     */
    void updateRole(Long id, UpdateRoleRequest request);

    /**
     * 删除角色，不能删除管理员
     *
     * @param id 角色id
     */
    void removeRole(Long id);

    /**
     * 给角色添加权限
     *
     * @param request 角色ID与权限ID集合
     */
    void addRolePermissions(AddRolePermissionsRequest request);

    /**
     * 给角色分配用户
     *
     * @param roleId  角色id
     * @param request 待分配的用户ID集合
     */
    void addRoleUsers(Long roleId, AddRoleUsersRequest request);

    /**
     * 给角色移除用户
     *
     * @param roleId  角色id
     * @param request 待移除的用户ID集合
     */
    void removeRoleUsers(Long roleId, RemoveRoleUsersRequest request);
}
