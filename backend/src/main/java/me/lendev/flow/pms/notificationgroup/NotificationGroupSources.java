package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 在调用方事务中读取和校验通知群组来源，按数值 ID 规范化各类选择。
 */
@Component
@RequiredArgsConstructor
class NotificationGroupSources {

    private final NotificationGroupStore store;

    Sources saved(long groupId) {
        return new Sources(store.users(groupId), store.departments(groupId));
    }

    List<GroupDepartment> departmentDetails(long groupId) {
        List<DepartmentSelection> selections = store.departments(groupId);
        if (selections.isEmpty()) {
            return List.of();
        }
        NotificationDepartmentTree tree = store.departments();
        return selections.stream().map(tree::detail).toList();
    }

    /**
     * 规范化输入为按数值 ID 排序的集合，同时拒绝重复值，避免比较与审计依赖提交顺序。
     */
    static Sources normalize(List<Long> users, List<DepartmentSelection> departments) {
        if (departments == null || departments.size() > MAX_DEPARTMENTS) {
            throw NotificationGroupFailure.invalidInput();
        }
        List<Long> userIds = uniqueIds(users, MAX_USERS, Integer.MAX_VALUE, "duplicate-source");
        var selections = new TreeMap<Long, DepartmentSelection>();
        for (DepartmentSelection department : departments) {
            if (department == null || department.departmentId() == null || department.departmentId() <= 0) {
                throw NotificationGroupFailure.invalidInput();
            }
            if (selections.putIfAbsent(department.departmentId(), department) != null) {
                throw NotificationGroupFailure.badRequest("duplicate-source");
            }
        }
        return new Sources(userIds, List.copyOf(selections.values()));
    }

    /**
     * 对新增用户、部门和范围已变化的部门批量校验实时状态。其余原有项可原样保留，
     * 保证管理员能清理历史配置而无需让已删除账号重新可用。
     */
    void validate(Sources sources, Sources previous, boolean required) {
        if (required && sources.users().isEmpty() && sources.departments().isEmpty()) {
            throw NotificationGroupFailure.badRequest("empty-sources");
        }
        Set<Long> previousUsers = new HashSet<>(previous.users());
        Set<DepartmentSelection> previousDepartments = new HashSet<>(previous.departments());
        List<Long> addedUsers = sources.users().stream().filter(id -> !previousUsers.contains(id)).toList();
        List<Long> newDepartments = sources.departments().stream().filter(selection -> !previousDepartments.contains(selection))
                .map(DepartmentSelection::departmentId).toList();
        if (!store.allUsersActive(addedUsers) || !store.allDepartmentsActive(newDepartments)) {
            throw NotificationGroupFailure.badRequest("invalid-source");
        }
    }

    static List<Long> sceneGroupIds(List<Long> groupIds) {
        return uniqueIds(groupIds, MAX_SCENE_GROUPS, Long.MAX_VALUE, "duplicate-group");
    }

    private static List<Long> uniqueIds(List<Long> ids, int sizeLimit, long largestId, String duplicateKey) {
        if (ids == null || ids.size() > sizeLimit) {
            throw NotificationGroupFailure.invalidInput();
        }
        var sorted = new TreeSet<Long>();
        for (Long id : ids) {
            if (id == null || id <= 0 || id > largestId) {
                throw NotificationGroupFailure.invalidInput();
            }
            if (!sorted.add(id)) {
                throw NotificationGroupFailure.badRequest(duplicateKey);
            }
        }
        return List.copyOf(sorted);
    }

    record Sources(List<Long> users, List<DepartmentSelection> departments) {
        static Sources empty() {
            return new Sources(List.of(), List.of());
        }
    }
}
