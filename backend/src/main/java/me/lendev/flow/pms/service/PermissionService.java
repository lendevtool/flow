package me.lendev.flow.pms.service;

import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.ResourceCatalogDto;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.request.CreatePermissionRequest;
import me.lendev.flow.pms.domain.request.UpdatePermissionRequest;
import cn.hutool.core.lang.tree.Tree;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 权限服务类
 *
 * @author lendev
 */
public interface PermissionService extends IService<Permission> {

    /**
     * 分页查询资源目录。
     *
     * @param pageNo   从 1 开始的页码
     * @param pageSize 已校验的每页条数
     * @return 资源目录当前页与总数
     */
    Page<ResourceCatalogDto> catalogPage(int pageNo, int pageSize);

    /**
     * 根据 ID 读取资源目录项。
     *
     * @param id 资源 ID
     * @return 资源目录项
     * @throws me.lendev.flow.common.exception.ResourceNotFoundException 资源不存在
     */
    ResourceCatalogDto catalogEntry(long id);

    /**
     * 根据角色id获取所有权限
     *
     * @param roleId 角色id
     * @return 权限
     */
    List<Permission> findByRoleId(Long roleId);


    /**
     * 新建权限
     *
     * @param request 请求
     */
    void create(CreatePermissionRequest request);

    /**
     * 更新权限：先校验父节点合法性并防止形成环
     *
     * @param id      权限id
     * @param request 更新权限请求
     */
    void updatePermission(Long id, UpdatePermissionRequest request);

    /**
     * 在同一事务中重排直属菜单和按钮权限，范围变化时拒绝整个请求。
     *
     * @param parentId 父菜单 ID，空值表示排列一级菜单
     * @param ids      当前范围内全部资源 ID，按目标顺序提供
     */
    void sortPermissions(Long parentId, List<Long> ids);

    /**
     * 删除叶子权限，并在同一事务内清除其角色关联
     *
     * @param id 权限id
     */
    void removePermission(Long id);

    /**
     * 批量创建
     *
     * @param request 创建权限请求列表
     */
    void createBatch(List<CreatePermissionRequest> request);

    /**
     * 查询所有菜单权限
     *
     * @return 菜单权限
     */
    List<PermissionDto> findAllMenu();

    /**
     * 查询所有菜单权限，并构建树
     *
     * @return 菜单权限树
     */
    List<Tree<Long>> findAllMenuTree();


    /**
     * 查询所有权限，并构建树
     *
     * @return 权限树
     */
    List<Tree<Long>> findAllTree();

    /**
     * 查询按钮
     *
     * @param parentId 父节点id
     * @return 权限列表
     */
    List<Permission> findButton(Long parentId);

    /**
     * 校验 path 存不存在menu资源内
     *
     * @param path path
     * @return 是否存在
     */
    boolean validateMenuPath(String path);
}
