package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.notificationgroup.mapper.NotificationGroupMapper;
import me.lendev.flow.pms.notificationgroup.persistence.NotificationGroupEntry;
import org.springframework.stereotype.Repository;

/**
 * 通知群组及场景的持久化边界。所有查询和写入使用当前 Spring 事务中的 MyBatis-Flex 会话。
 */
@Repository
@RequiredArgsConstructor
public class NotificationGroupStore {

    private final NotificationGroupMapper mapper;

    /**
     * 读取群组主行；审计与写操作可在同一事务中对指定主键加行锁。
     *
     * @param id   数据库中的群组主键
     * @param lock 是否执行排他锁读
     * @return 主行；群组不存在时返回 {@code null}
     */
    public GroupRow group(long id, boolean lock) {
        return mapper.group(id, lock);
    }

    /**
     * 读取场景主行；场景写入时须先锁定本行，再获取任何群组锁。
     *
     * @param code 已登记的场景编码
     * @param lock 是否执行排他锁读
     * @return 主行；场景不存在时返回 {@code null}
     */
    public SceneRow scene(String code, boolean lock) {
        return mapper.scene(code, lock);
    }

    /**
     * @return 群组凭配置保留的指定用户 ID，按数值升序，不检查实时账号状态
     */
    public List<Long> users(long id) {
        return users(List.of(id));
    }

    /**
     * @return 群组凭配置保留的部门范围，按数值 ID 升序，不检查实时部门状态
     */
    public List<DepartmentSelection> departments(long id) {
        return departments(List.of(id));
    }

    /**
     * @return 场景已绑定的群组 ID，按数值升序
     */
    public List<Long> groupIds(String code) {
        return mapper.groupIds(code);
    }

    List<GroupRow> groups(List<Long> groupIds) {
        return groupIds.isEmpty() ? List.of() : mapper.groups(groupIds);
    }

    List<Long> users(List<Long> groupIds) {
        return groupIds.isEmpty() ? List.of() : mapper.users(groupIds);
    }

    List<DepartmentSelection> departments(List<Long> groupIds) {
        return groupIds.isEmpty() ? List.of() : mapper.departments(groupIds);
    }

    Long groupIdForName(String name) {
        return mapper.groupIdForName(name);
    }

    Page<GroupListItem> pageGroups(Query query) {
        String keyword = likePattern(query.keyword());
        return page(mapper.countGroups(keyword, query.enabled()), query,
                () -> mapper.pageGroups(keyword, query.enabled(), query.offset(), query.limit()).stream()
                        .map(GroupListRow::toItem).toList());
    }

    Page<GroupOption> pageOptions(Query query) {
        String keyword = likePattern(query.keyword());
        return page(mapper.countGroups(keyword, true), query,
                () -> mapper.pageOptions(keyword, true, query.offset(), query.limit()));
    }

    long insertGroup(String name, String description, int actor, LocalDateTime now) {
        var row = new NotificationGroupEntry();
        row.setName(name);
        row.setDescription(description);
        row.setEnabled(true);
        row.setVersion(0L);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
        return row.getId();
    }

    void updateGroup(long id, String name, String description, int actor, LocalDateTime now) {
        mapper.updateGroup(id, name, description, actor, now);
    }

    void setEnabled(long id, boolean enabled, int actor, LocalDateTime now) {
        mapper.setEnabled(id, enabled, actor, now);
    }

    List<GroupUser> groupUsers(long id) {
        return mapper.groupUsers(id);
    }

    void replaceUsers(long id, Collection<Long> users) {
        mapper.clearUsers(id);
        if (!users.isEmpty()) {
            mapper.addUsers(id, List.copyOf(users));
        }
    }

    void replaceDepartments(long id, Collection<DepartmentSelection> departments) {
        mapper.clearDepartments(id);
        if (!departments.isEmpty()) {
            mapper.addDepartments(id, List.copyOf(departments));
        }
    }

    boolean allUsersActive(List<Long> userIds) {
        return userIds.isEmpty() || mapper.activeUsers(userIds).size() == userIds.size();
    }

    boolean allDepartmentsActive(List<Long> departmentIds) {
        return departmentIds.isEmpty() || mapper.activeDepartments(departmentIds).size() == departmentIds.size();
    }

    NotificationDepartmentTree departments() {
        return new NotificationDepartmentTree(mapper.departmentTree());
    }

    Page<UserOption> pageUserCandidates(Query query) {
        String keyword = likePattern(query.keyword());
        return page(mapper.countUserCandidates(keyword), query,
                () -> mapper.pageUserCandidates(keyword, query.offset(), query.limit()));
    }

    long usageCount(long id) {
        return mapper.usageCount(id);
    }

    Page<SceneOption> pageUsages(long id, Query query) {
        return page(usageCount(id), query, () -> mapper.pageUsages(id, query.offset(), query.limit()));
    }

    Page<SceneListItem> pageScenes(Query query) {
        return page(mapper.countScenes(), query, () -> mapper.pageScenes(query.offset(), query.limit()));
    }

    void replaceSceneGroups(String code, Collection<Long> groupIds, int actor, LocalDateTime now) {
        mapper.clearSceneGroups(code);
        if (!groupIds.isEmpty()) {
            mapper.addSceneGroups(code, List.copyOf(groupIds));
        }
        mapper.touchScene(code, actor, now);
    }

    Page<UserOption> pageRecipients(RecipientScope scope, Query query) {
        return page(scope.isEmpty() ? 0 : mapper.countRecipients(scope), query,
                () -> mapper.pageRecipients(scope, query.offset(), query.limit()));
    }

    List<Long> recipientIds(RecipientScope scope, int limit) {
        return scope.isEmpty() ? List.of() : mapper.recipientIds(scope, limit);
    }

    private static <T> Page<T> page(long total, Query query, Supplier<List<T>> rows) {
        var page = new Page<T>();
        page.setTotal(total);
        page.setPageData(query.offset() >= total ? List.of() : rows.get());
        return page;
    }

    private static String likePattern(String keyword) {
        return keyword == null || keyword.isBlank() ? null
                : "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public record GroupRow(Long id, String name, String description, boolean enabled, long version,
                           int createdBy, int updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt) {

    }

    public record GroupListRow(Long id, String name, String description, boolean enabled, long version,
                               long userCount, long departmentCount, long usageCount, LocalDateTime updatedAt) {

        GroupListItem toItem() {
            return new GroupListItem(id, name, description, enabled, version, userCount, departmentCount, usageCount,
                    updatedAt.atOffset(ZoneOffset.UTC));
        }
    }

    public record SceneRow(String sceneCode, String nameCn, String nameEn, long version, String sceneType,
                           boolean enabled, String titleTemplate, String summaryTemplate, Long mailTemplateId) {

    }

    public record DepartmentRow(Long id, Long parentId, String name, int sortOrder, boolean enabled) {

    }

    /**
     * 供 SQL 一次绑定两组数值 ID；MySQL 使用 JSON 数据，H2 使用 SQL 数组。
     */
    public record RecipientScope(boolean h2, Long[] userIds, Long[] departmentIds, String userJson,
                                 String departmentJson) {

        public RecipientScope {
            userIds = copyIds(userIds);
            departmentIds = copyIds(departmentIds);
        }

        static RecipientScope of(boolean h2, Collection<Long> users, Set<Long> departments) {
            return h2
                    ? new RecipientScope(true, users.toArray(Long[]::new), departments.toArray(Long[]::new), null, null)
                    : new RecipientScope(false, null, null, users.toString(), departments.toString());
        }

        public boolean hasUsers() {
            return h2 ? userIds.length != 0 : !"[]".equals(userJson);
        }

        public boolean hasDepartments() {
            return h2 ? departmentIds.length != 0 : !"[]".equals(departmentJson);
        }

        boolean isEmpty() {
            return !hasUsers() && !hasDepartments();
        }

        @Override
        public Long[] userIds() {
            return copyIds(userIds);
        }

        @Override
        public Long[] departmentIds() {
            return copyIds(departmentIds);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            RecipientScope scope = (RecipientScope) other;
            return h2 == scope.h2
                    && Arrays.equals(userIds, scope.userIds)
                    && Arrays.equals(departmentIds, scope.departmentIds)
                    && Objects.equals(userJson, scope.userJson)
                    && Objects.equals(departmentJson, scope.departmentJson);
        }

        @Override
        public int hashCode() {
            return Objects.hash(h2, Arrays.hashCode(userIds), Arrays.hashCode(departmentIds), userJson, departmentJson);
        }

        @Override
        public String toString() {
            return "RecipientScope[h2=" + h2 + ", userIds=" + Arrays.toString(userIds)
                    + ", departmentIds=" + Arrays.toString(departmentIds)
                    + ", userJson=" + userJson + ", departmentJson=" + departmentJson + "]";
        }

        private static Long[] copyIds(Long[] ids) {
            return ids == null ? null : ids.clone();
        }
    }
}
