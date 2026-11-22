package me.lendev.flow.pms.service.impl;

import static me.lendev.flow.common.validation.EnglishName.normalizeEnglishName;
import static me.lendev.flow.pms.domain.entity.table.PermissionTableDef.PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditOperation;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.ResourceNotFoundException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.domain.request.UpdatePermissionRequest;
import me.lendev.flow.pms.service.RolePermissionService;
import me.lendev.flow.pms.service.support.AssignmentValidator;
import me.lendev.flow.pms.convert.PermissionConverter;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.ResourceCatalogDto;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.request.CreatePermissionRequest;
import me.lendev.flow.pms.mapper.PermissionMapper;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.util.PermissionUtil;
import cn.hutool.core.lang.tree.Tree;
import com.mybatisflex.spring.service.impl.ServiceImpl;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限服务类的实现类，主要负责权限相关的处理
 *
 * @author lendev
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionServiceImpl extends ServiceImpl<PermissionMapper, Permission>
        implements PermissionService {

    private final PermissionConverter permissionConverter;
    private final RolePermissionService rolePermissionService;
    private final AssignmentValidator assignmentValidator;

    private static final String TYPE_MENU = "MENU";
    private static final String TYPE_BUTTON = "BUTTON";

    @Override
    public Page<ResourceCatalogDto> catalogPage(int pageNo, int pageSize) {
        var page = com.mybatisflex.core.paginate.Page.<Permission>of(pageNo, pageSize);
        return Page.convert(getMapper().pageCatalog(page).map(PermissionServiceImpl::toCatalogEntry));
    }

    @Override
    public ResourceCatalogDto catalogEntry(long id) {
        Permission permission = getMapper().findCatalogById(id);
        if (permission == null) {
            throw new ResourceNotFoundException();
        }
        return toCatalogEntry(permission);
    }

    private static ResourceCatalogDto toCatalogEntry(Permission permission) {
        return new ResourceCatalogDto(permission.getId(), permission.getParentId(), permission.getCode(),
                permission.getName(), permission.getNameEn(), permission.getType(),
                Boolean.TRUE.equals(permission.getEnable()), permission.getOrder());
    }

    @Override
    public List<Permission> findByRoleId(Long roleId) {
        return getMapper().findByRoleId(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.PERMISSION_CREATE)
    public void create(CreatePermissionRequest request) {
        validateParent(null, request.getParentId());
        Permission permission = toNewPermission(request);
        this.save(permission);
        log.info("资源权限已写入，requestId={} permissionId={} parentId={}",
                AuditParticipants.currentRequestId(), permission.getId(), request.getParentId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.PERMISSION_BATCH_CREATE)
    public void createBatch(List<CreatePermissionRequest> request) {
        for (CreatePermissionRequest item : request) {
            validateParent(null, item.getParentId());
        }
        List<Permission> list = request.stream().map(this::toNewPermission)
                .toList();
        this.saveBatch(list);
        log.info("资源权限批量写入结束，requestId={} permissionCount={}", AuditParticipants.currentRequestId(), list.size());
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.PERMISSION_UPDATE)
    public void updatePermission(Long id, UpdatePermissionRequest request) {
        requirePermission(id);
        validateParent(id, request.getParentId());
        Permission permission = UpdateEntity.ofNotNull(permissionConverter.toPermission(request));
        permission.setId(id);
        if (request.getNameEn() != null) {
            permission.setNameEn(normalizeEnglishName(request.getNameEn()));
        }
        updateById(permission);
        log.info("资源权限已更新，requestId={} permissionId={}", AuditParticipants.currentRequestId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.PERMISSION_SORT)
    public void sortPermissions(Long parentId, List<Long> ids) {
        if (parentId != null && !TYPE_MENU.equals(requirePermission(parentId).getType())) {
            throw BadRequestException.localized("error.permission.sort.parent");
        }
        Set<Long> existing = getMapper().lockSortableChildren(parentId).stream()
                .map(Permission::getId).collect(Collectors.toSet());
        if (ids.size() != existing.size() || !existing.equals(new HashSet<>(ids))) {
            throw BadRequestException.localized("error.permission.sort.invalid");
        }
        for (int i = 0; i < ids.size(); i++) {
            Permission patch = new Permission();
            patch.setId(ids.get(i));
            patch.setOrder(i + 1);
            updateById(patch);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.PERMISSION_DELETE)
    public void removePermission(Long id) {
        requirePermission(id);
        if (!getMapper().selectListByQuery(QueryWrapper.create().where(PERMISSION.PARENT_ID.eq(id))
                .orderBy(PERMISSION.ID.asc()).limit(1).forUpdate()).isEmpty()) {
            throw BadRequestException.localized("error.permission.has-children");
        }
        rolePermissionService.remove(QueryWrapper.create().and(ROLE_PERMISSION.PERMISSION_ID.eq(id, true)));
        removeById(id);
        log.info("资源权限及其角色关联已删除，requestId={} permissionId={}", AuditParticipants.currentRequestId(), id);
    }

    private Permission toNewPermission(CreatePermissionRequest request) {
        Permission permission = permissionConverter.toPermission(request);
        permission.setNameEn(normalizeEnglishName(request.getNameEn()));
        return permission;
    }

    private Permission requirePermission(Long id) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.permission.id.invalid");
        }
        Permission permission = getMapper().selectOneByQuery(QueryWrapper.create().where(PERMISSION.ID.eq(id)).forUpdate());
        if (permission == null) {
            throw BadRequestException.localized("error.permission.missing");
        }
        return permission;
    }

    private void validateParent(Long id, Long parentId) {
        if (parentId == null) {
            return;
        }
        assignmentValidator.requirePermissions(List.of(parentId));
        // 沿父链向上回溯：visited 去重可防止存量脏数据成环导致死循环；
        // 若链上遇到自身 id，说明本次变更会把树接成环，必须拒绝。
        Set<Long> visited = new HashSet<>();
        Long currentId = parentId;
        while (currentId != null) {
            if (currentId.equals(id) || !visited.add(currentId)) {
                throw BadRequestException.localized("error.permission.parent.cycle");
            }
            Permission parent = requirePermission(currentId);
            currentId = parent.getParentId();
        }
    }

    @Override
    public List<PermissionDto> findAllMenu() {
        return queryChain().and(PERMISSION.TYPE.eq(TYPE_MENU, true))
                .list()
                .stream()
                .map(permissionConverter::toPermissionDto)
                .toList();
    }

    @Override
    public List<Tree<Long>> findAllMenuTree() {
        List<Permission> permissions = queryChain().and(PERMISSION.TYPE.eq(TYPE_MENU, true))
                .orderBy(PERMISSION.ORDER.asc())
                .list();
        return PermissionUtil.toTreeNode(permissions, null);
    }

    @Override
    public List<Tree<Long>> findAllTree() {
        List<Permission> permissions = queryChain()
                .orderBy(PERMISSION.ORDER.asc())
                .list();
        return PermissionUtil.toTreeNode(permissions, null);
    }

    @Override
    public List<Permission> findButton(Long parentId) {
        return queryChain()
                .and(PERMISSION.PARENT_ID.eq(parentId, true))
                .and(PERMISSION.TYPE.in(TYPE_BUTTON))
                .orderBy(PERMISSION.ORDER.asc())
                .list();
    }

    @Override
    public boolean validateMenuPath(String path) {
        return queryChain().and(PERMISSION.PATH.eq(path, true)).exists();
    }

}
