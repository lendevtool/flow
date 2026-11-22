package me.lendev.flow.param.audit;

import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import com.mybatisflex.core.query.QueryWrapper;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentDimensions;
import me.lendev.flow.param.domain.*;
import me.lendev.flow.param.mapper.*;
import me.lendev.flow.param.request.*;
import me.lendev.flow.param.support.TodoDimensionParameters;
import org.springframework.stereotype.Component;

/**
 * 参数模块的审计处理器：只负责参数聚合特有的快照捕获与校验语义， 调用、序列化、重试等通用逻辑均保留在公共 audit 模块中。
 */
@Component
@RequiredArgsConstructor
public class ParamAuditHandler implements AuditHandler {

    private final ParamGroupMapper groups;
    private final ParamItemMapper items;
    private final ParamAuditSnapshots captures;
    private final AuditSnapshots changes;
    private final DocumentDimensions documentDimensions;
    private final TodoDimensionParameters todoDimensions;

    @Override
    public Set<AuditOperation> operations() {
        return EnumSet.range(AuditOperation.PARAM_GROUP_CREATE, AuditOperation.PARAM_ITEM_SORT);
    }

    @Override
    public void validate(Method method) {
        AuditOperation operation = method.getAnnotation(Audited.class).value();
        Class<?>[] expected = switch (operation) {
            case PARAM_GROUP_CREATE -> new Class<?>[]{ParamGroupRequest.class};
            case PARAM_GROUP_UPDATE -> new Class<?>[]{Long.class, ParamGroupRequest.class};
            case PARAM_GROUP_DELETE -> new Class<?>[]{Long.class};
            case PARAM_GROUP_SORT -> new Class<?>[]{ParamSortRequest.class};
            case PARAM_ITEM_CREATE -> new Class<?>[]{Long.class, ParamItemRequest.class};
            case PARAM_ITEM_UPDATE -> new Class<?>[]{Long.class, Long.class, ParamItemRequest.class};
            case PARAM_ITEM_DELETE -> new Class<?>[]{Long.class, Long.class};
            case PARAM_ITEM_SORT -> new Class<?>[]{Long.class, ParamSortRequest.class};
            default -> throw new IllegalStateException("参数审计操作绑定无效");
        };
        Class<?> result = switch (operation) {
            case PARAM_GROUP_CREATE, PARAM_GROUP_UPDATE -> ParamGroup.class;
            case PARAM_ITEM_CREATE, PARAM_ITEM_UPDATE -> ParamItem.class;
            default -> void.class;
        };
        if (!Arrays.equals(expected, method.getParameterTypes()) || method.getReturnType() != result) {
            throw new IllegalStateException("参数审计方法签名与注册定义不一致: " + method.getName());
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        lockConfiguration(operation, args);
        return switch (operation) {
            case PARAM_GROUP_CREATE -> result -> {
                ParamGroup saved = (ParamGroup) result;
                return List.of(changes.created(captures.group(group(saved.getId()))));
            };
            case PARAM_GROUP_UPDATE, PARAM_GROUP_DELETE -> groupChange(operation, (Long) args[0]);
            case PARAM_GROUP_SORT -> groupSort();
            case PARAM_ITEM_CREATE, PARAM_ITEM_UPDATE, PARAM_ITEM_DELETE -> itemChange(operation, args);
            case PARAM_ITEM_SORT -> itemSort((Long) args[0]);
            default -> throw new IllegalStateException("参数审计操作未绑定");
        };
    }

    private void lockConfiguration(AuditOperation operation, Object[] args) {
        // 在读取加锁快照之前，保持文档配置、待办配置、目标分类和明细的加锁顺序。
        switch (operation) {
            case PARAM_GROUP_SORT -> {
                documentDimensions.lockConfiguration();
                todoDimensions.slotGroup(true);
            }
            case PARAM_GROUP_DELETE -> {
                documentDimensions.lockConfiguration();
                Long id = (Long) args[0];
                ParamGroup target = id == null ? null : groups.selectOneById(id);
                if (target != null && TodoDimensionParameters.isDictionary(target.getCode())) {
                    todoDimensions.slotGroup(true);
                }
            }
            case PARAM_ITEM_DELETE -> documentDimensions.lockConfiguration();
            default -> {
                // 其他写操作只需锁定目标分类。
            }
        }
    }

    private Plan groupChange(AuditOperation operation, Long id) {
        var before = captures.group(group(id));
        return result -> {
            if (operation == AuditOperation.PARAM_GROUP_DELETE) {
                if (findGroup(id) != null) {
                    throw new IllegalStateException("参数分类删除结果不一致");
                }
                return List.of(changes.deleted(before));
            }
            return List.of(changes.updated(before, captures.group(group(id))));
        };
    }

    private Plan itemChange(AuditOperation operation, Object[] args) {
        Long groupId = (Long) args[0];
        ParamGroup group = group(groupId); // 统一加锁顺序：先锁聚合根（分类），再锁子记录（明细），避免死锁。
        if (operation == AuditOperation.PARAM_ITEM_CREATE) {
            return result -> List.of(changes.created(captures.item(group, item(groupId, ((ParamItem) result).getId()))));
        }
        Long id = (Long) args[1];
        var before = captures.item(group, item(groupId, id));
        return result -> {
            if (operation == AuditOperation.PARAM_ITEM_DELETE) {
                if (findItem(id) != null) {
                    throw new IllegalStateException("参数明细删除结果不一致");
                }
                return List.of(changes.deleted(before));
            }
            return List.of(changes.updated(before, captures.item(group, item(groupId, id))));
        };
    }

    private Plan groupSort() {
        Map<Long, AuditSnapshots.Capture> before = captureOrder(allGroups());
        return result -> compareOrder(before, allGroups(),
                new AuditObject("PARAM_GROUP_SET", "ALL", null, "参数分类", null, null));
    }

    private Plan itemSort(Long groupId) {
        AuditObject owner = captures.ref(group(groupId));
        Map<Long, AuditSnapshots.Capture> before = captureOrder(allItems(groupId));
        return result -> compareOrder(before, allItems(groupId), owner);
    }

    private Map<Long, AuditSnapshots.Capture> captureOrder(List<? extends ParamRecord> records) {
        Map<Long, AuditSnapshots.Capture> before = new LinkedHashMap<>();
        for (ParamRecord row : records) {
            before.put(row.getId(), captures.order(row));
        }
        return before;
    }

    private List<AuditChange> compareOrder(Map<Long, AuditSnapshots.Capture> before,
                                           List<? extends ParamRecord> persisted, AuditObject emptyOwner) {
        Set<Long> ids = persisted.stream().map(ParamRecord::getId).collect(Collectors.toSet());
        if (!before.keySet().equals(ids)) {
            throw BadRequestException.localized("error.param.sort.stale");
        }
        List<AuditChange> details = persisted.stream()
                .map(row -> changes.updated(before.get(row.getId()), captures.order(row))).toList();
        return details.isEmpty() ? List.of(changes.reference(emptyOwner)) : details;
    }

    private ParamGroup group(Long id) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.param.group.id.invalid");
        }
        ParamGroup group = findGroup(id);
        if (group == null) {
            throw BadRequestException.localized("error.param.group.missing");
        }
        return group;
    }

    private ParamGroup findGroup(Long id) {
        return groups.selectOneByQuery(QueryWrapper.create().where(PARAM_GROUP.ID.eq(id)).forUpdate());
    }

    private ParamItem item(Long groupId, Long id) {
        ParamItem item = findItem(id);
        if (item == null || !groupId.equals(item.getGroupId())) {
            throw BadRequestException.localized("error.param.item.missing");
        }
        return item;
    }

    private ParamItem findItem(Long id) {
        if (id == null) {
            return null;
        }
        return items.selectOneByQuery(QueryWrapper.create().where(PARAM_ITEM.ID.eq(id)).forUpdate());
    }

    private List<ParamGroup> allGroups() {
        var records = groups.selectListByQuery(QueryWrapper.create().orderBy(PARAM_GROUP.ID.asc()).limit(5001).forUpdate());
        if (records.size() > 5000) {
            throw BadRequestException.localized("error.param.group.sort.limit");
        }
        return records;
    }

    private List<ParamItem> allItems(Long groupId) {
        var records = items.selectListByQuery(QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(groupId))
                .orderBy(PARAM_ITEM.ID.asc()).limit(5001).forUpdate());
        if (records.size() > 5000) {
            throw BadRequestException.localized("error.param.item.sort.limit");
        }
        return records;
    }
}
