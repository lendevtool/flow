package me.lendev.flow.pms.audit;

import static me.lendev.flow.pms.domain.entity.table.PermissionTableDef.PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.request.*;
import me.lendev.flow.pms.mapper.*;
import org.springframework.stereotype.Component;

/**
 * 权限（资源）操作的审计处理器。 在业务方法执行前后分别拍摄快照生成审计变更；查询统一使用 {@code forUpdate} 加行锁， 保证同一事务内快照与业务修改读到一致的数据，防止并发修改导致审计失真。
 */
@Component
@RequiredArgsConstructor
public class PermissionAuditHandler implements AuditHandler {

    private final PermissionMapper permissions;
    private final RolePermissionMapper relations;
    private final PmsAuditSnapshots captures;
    private final AuditSnapshots changes;

    public Set<AuditOperation> operations() {
        return EnumSet.range(AuditOperation.PERMISSION_CREATE, AuditOperation.PERMISSION_SORT);
    }

    @Override
    public void validate(java.lang.reflect.Method method) {
        switch (method.getAnnotation(Audited.class).value()) {
            case PERMISSION_CREATE -> PmsAuditSnapshots.binding(method, CreatePermissionRequest.class);
            case PERMISSION_BATCH_CREATE -> PmsAuditSnapshots.binding(method, List.class);
            case PERMISSION_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, UpdatePermissionRequest.class);
            case PERMISSION_DELETE -> PmsAuditSnapshots.binding(method, Long.class);
            case PERMISSION_SORT -> PmsAuditSnapshots.binding(method, Long.class, List.class);
            default -> throw new IllegalStateException("资源操作未注册");
        }
    }

    public Plan prepare(AuditOperation operation, Object[] args) {
        if (operation == AuditOperation.PERMISSION_CREATE || operation == AuditOperation.PERMISSION_BATCH_CREATE) {
            return prepareCreation(operation, args[0]);
        }
        Long id = (Long) args[0];
        if (operation == AuditOperation.PERMISSION_SORT) {
            return prepareSort(id);
        }
        var before = captures.permission(require(id), operation == AuditOperation.PERMISSION_DELETE ? roleIds(id) : null);
        return result -> {
            if (operation == AuditOperation.PERMISSION_DELETE) {
                if (find(id) != null) {
                    throw new IllegalStateException("资源删除结果不一致");
                }
                return List.of(changes.deleted(before));
            }
            return List.of(changes.updated(before, captures.permission(require(id), null)));
        };
    }

    private Plan prepareSort(Long parentId) {
        // 先锁定父菜单，避免排序期间新增子资源。
        if (parentId != null) {
            require(parentId);
        }
        Map<Long, AuditSnapshots.Capture> before = new HashMap<>();
        for (Permission row : permissions.lockSortableChildren(parentId)) {
            before.put(row.getId(), order(row));
        }
        return result -> {
            var rows = permissions.lockSortableChildren(parentId);
            if (rows.size() != before.size() || rows.stream().anyMatch(row -> !before.containsKey(row.getId()))) {
                throw BadRequestException.localized("error.permission.sort.invalid");
            }
            return rows.stream().map(row -> changes.updated(before.get(row.getId()), order(row))).toList();
        };
    }

    private AuditSnapshots.Capture order(Permission row) {
        return changes.capture(captures.permissionRef(row), Map.of("order", AuditField.visible("排序", row.getOrder())));
    }

    private Plan prepareCreation(AuditOperation operation, Object argument) {
        @SuppressWarnings("unchecked")
        List<CreatePermissionRequest> requests = operation == AuditOperation.PERMISSION_CREATE
                ? List.of((CreatePermissionRequest) argument) : (List<CreatePermissionRequest>) argument;
        List<String> codes = requests.stream().map(CreatePermissionRequest::getCode).toList();
        return result -> {
            if (codes.isEmpty()) {
                return List.of(changes.reference(new me.lendev.flow.audit.model.AuditObject("PERMISSION_SET", "ALL", null, "资源集合", null, null)));
            }
            var records = permissions.selectListByQuery(QueryWrapper.create().where(PERMISSION.CODE.in(codes)).orderBy(PERMISSION.ID.asc()).forUpdate());
            if (records.size() != codes.size()) {
                throw new IllegalStateException("资源创建结果集合不一致");
            }
            return records.stream().map(row -> changes.created(captures.permission(row, null))).toList();
        };
    }

    private List<Long> roleIds(Long id) {
        return relations.selectListByQuery(QueryWrapper.create().where(ROLE_PERMISSION.PERMISSION_ID.eq(id))
                .orderBy(ROLE_PERMISSION.ROLE_ID.asc()).limit(5001).forUpdate()).stream().map(row -> row.getRoleId()).toList();
    }

    private Permission require(Long id) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.permission.id.invalid");
        }
        var resource = find(id);
        if (resource == null) {
            throw BadRequestException.localized("error.permission.missing");
        }
        return resource;
    }

    private Permission find(Long id) {
        return permissions.selectOneByQuery(QueryWrapper.create().where(PERMISSION.ID.eq(id)).forUpdate());
    }
}
