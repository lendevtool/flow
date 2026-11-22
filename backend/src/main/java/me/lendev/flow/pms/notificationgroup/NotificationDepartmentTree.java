package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.DepartmentRow;

/**
 * 将一次批量读取的部门快照用于配置回显和运行时展开。
 */
final class NotificationDepartmentTree {

    private final List<DepartmentRow> rows;
    private final Map<Long, DepartmentRow> nodes = new HashMap<>();
    private final Map<Long, List<Long>> children = new HashMap<>();

    NotificationDepartmentTree(List<DepartmentRow> rows) {
        this.rows = rows;
        for (DepartmentRow row : rows) {
            nodes.put(row.id(), row);
            if (row.parentId() != null) {
                children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(row.id());
            }
        }
    }

    List<DepartmentOption> candidates() {
        return rows.stream().filter(DepartmentRow::enabled)
                .map(row -> new DepartmentOption(row.id(), row.parentId(), row.name(), row.sortOrder())).toList();
    }

    GroupDepartment detail(DepartmentSelection selection) {
        DepartmentRow row = nodes.get(selection.departmentId());
        boolean includeDescendants = Boolean.TRUE.equals(selection.includeDescendants());
        if (row == null) {
            return new GroupDepartment(selection.departmentId(), null, null, includeDescendants, SourceStatus.MISSING);
        }
        SourceStatus status = row.enabled() ? SourceStatus.ACTIVE : SourceStatus.DISABLED;
        return new GroupDepartment(selection.departmentId(), row.name(), path(row), includeDescendants, status);
    }

    /**
     * 扩展有效部门集合。直接来源只计直属节点；递归来源遇停用节点即终止该分支。
     * 单独跟踪已遍历的递归节点，避免先命中直属节点时漏掉后续的子树选择。
     *
     * @param selections 可来自多个群组，允许配置范围重叠
     * @return 去重后的部门 ID
     */
    Set<Long> expand(Collection<DepartmentSelection> selections) {
        Set<Long> result = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        for (DepartmentSelection selection : selections) {
            if (Boolean.TRUE.equals(selection.includeDescendants())) {
                pending.add(selection.departmentId());
            } else {
                DepartmentRow row = nodes.get(selection.departmentId());
                if (row != null && row.enabled()) {
                    result.add(row.id());
                }
            }
        }
        while (!pending.isEmpty()) {
            long id = pending.removeFirst();
            DepartmentRow row = nodes.get(id);
            if (row != null && row.enabled() && visited.add(id)) {
                result.add(id);
                pending.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return result;
    }

    private String path(DepartmentRow row) {
        Deque<String> names = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        for (DepartmentRow cursor = row; cursor != null && visited.add(cursor.id()); cursor = nodes.get(cursor.parentId())) {
            names.addFirst(cursor.name());
        }
        return String.join(" / ", names);
    }
}
