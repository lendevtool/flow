package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditContext;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.notificationgroup.NotificationGroupSources.Sources;
import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.GroupRow;
import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.SceneRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 校验和维护通知群组配置及场景绑定；预览在独立的只读快照中执行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationGroupService {

    private final NotificationGroupStore store;
    private final NotificationGroupSources groupSources;
    private final NotificationReadSnapshot snapshot;
    private final AuditContext auditContext;
    private final Clock clock;

    /**
     * 分页读取群组摘要，包含三类配置计数。
     */
    public Page<GroupListItem> list(Query query) {
        return snapshot.read(() -> store.pageGroups(query));
    }

    /**
     * 获取群组配置及实时来源状态。
     */
    public GroupDetail detail(Long id) {
        return snapshot.read(() -> detail(requireGroup(id, false)));
    }

    /**
     * 创建默认启用、版本为 0 的群组；两类来源至少选择一项，且新来源必须有效。
     * 保存配置和审计入队共享同一事务。
     */
    @Audited(AuditOperation.NOTIFICATION_GROUP_CREATE)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public GroupDetail createGroup(CreateGroupRequest request) {
        long start = System.nanoTime();
        String name = normalizedName(request.name());
        String description = normalizedDescription(request.description());
        Sources sources = NotificationGroupSources.normalize(request.userIds(), request.departments());
        groupSources.validate(sources, Sources.empty(), true);
        requireAvailableName(name, null);
        long id;
        try {
            id = store.insertGroup(name, description, actorId(), now());
        } catch (DuplicateKeyException error) {
            throw NotificationGroupFailure.nameConflict();
        }
        store.replaceUsers(id, sources.users());
        store.replaceDepartments(id, sources.departments());
        GroupDetail detail = detail(requireGroup(id, false));
        logGroup("新建", detail, true, start);
        return detail;
    }

    /**
     * 锁定群组并核对版本后替换配置。已失效来源只可原样保留或移除。
     * 集合的提交顺序不影响变更判定，空变更保留原版本与时间。
     */
    @Audited(AuditOperation.NOTIFICATION_GROUP_UPDATE)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public GroupDetail updateGroup(Long id, UpdateGroupRequest request) {
        long start = System.nanoTime();
        GroupRow group = requireGroup(id, true);
        requireVersion(group.version(), request.expectedVersion());
        String name = normalizedName(request.name());
        String description = normalizedDescription(request.description());
        Sources sources = NotificationGroupSources.normalize(request.userIds(), request.departments());
        Sources previous = groupSources.saved(id);
        groupSources.validate(sources, previous, true);

        boolean usersChanged = !previous.users().equals(sources.users());
        boolean departmentsChanged = !previous.departments().equals(sources.departments());
        boolean nameChanged = !group.name().equals(name);
        boolean changed = usersChanged || departmentsChanged || nameChanged || !Objects.equals(group.description(), description);
        if (changed) {
            saveGroupConfiguration(group, name, description, sources, nameChanged, usersChanged, departmentsChanged);
        }
        GroupDetail detail = detail(changed ? requireGroup(id, false) : group);
        logGroup("更新", detail, changed, start);
        return detail;
    }

    /**
     * 锁定群组并校验版本后调整启用状态。停用时只查询关联表，以群组行锁与场景绑定串行。
     * 仍有引用时拒绝停用，并返回当前引用数。
     */
    @Audited(AuditOperation.NOTIFICATION_GROUP_STATUS_UPDATE)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public GroupDetail setGroupEnabled(Long id, GroupStatusRequest request) {
        long start = System.nanoTime();
        GroupRow group = requireGroup(id, true);
        requireVersion(group.version(), request.expectedVersion());
        if (request.enabled() == null) {
            throw NotificationGroupFailure.invalidInput();
        }
        boolean enabled = request.enabled();
        boolean changed = group.enabled() != enabled;
        if (changed) {
            if (!enabled) {
                long usages = store.usageCount(id);
                if (usages > 0) {
                    throw NotificationGroupFailure.groupInUse(usages);
                }
            }
            store.setEnabled(id, enabled, actorId(), now());
        }
        GroupDetail detail = detail(changed ? requireGroup(id, false) : group);
        logGroup(enabled ? "启用" : "停用", detail, changed, start);
        return detail;
    }

    /**
     * 按场景行、旧新群组主键升序的顺序获取锁，再检查目标组均为启用状态。
     * 相同绑定集合保持原版本，空集合解除全部绑定。
     */
    @Audited(AuditOperation.NOTIFICATION_SCENE_GROUPS_REPLACE)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SceneDetail replaceSceneGroups(String sceneCode, ReplaceSceneGroupsRequest request) {
        long start = System.nanoTime();
        SceneRow scene = requireScene(sceneCode, true);
        if (WorkflowSceneCatalog.SYSTEM.equals(scene.sceneType())) {
            throw NotificationGroupFailure.badRequest("workflow-scene-system-groups");
        }
        requireVersion(scene.version(), request.expectedVersion());
        List<Long> desired = NotificationGroupSources.sceneGroupIds(request.groupIds());
        List<Long> current = store.groupIds(sceneCode);
        lockAndValidateGroups(current, desired);

        boolean changed = !current.equals(desired);
        if (changed) {
            store.replaceSceneGroups(sceneCode, desired, actorId(), now());
        }
        SceneDetail detail = sceneDetail(changed ? requireScene(sceneCode, false) : scene);
        String requestId = AuditParticipants.currentRequestId();
        afterCommit(() -> log.info("通知场景绑定已提交，sceneCode={} version={} groupCount={} changed={} requestId={} elapsedMs={}",
                sceneCode, detail.version(), detail.groups().size(), changed, requestId, elapsedMs(start)));
        return detail;
    }

    /**
     * 分页预览已保存群组，停用组也可预览；实时失效来源不会出现在结果中。
     */
    public Page<UserOption> recipients(Long id, Query query) {
        return snapshot.read(() -> {
            requireGroup(id, false);
            return recipients(groupSources.saved(id), query);
        });
    }

    /**
     * 检查草稿新增来源并按同一组织快照去重分页；空草稿返回空名单。
     */
    public Page<UserOption> preview(PreviewRequest request) {
        return snapshot.read(() -> {
            Sources previous = Sources.empty();
            if (request.groupId() != null) {
                requireGroup(request.groupId(), false);
                previous = groupSources.saved(request.groupId());
            }
            Sources draft = NotificationGroupSources.normalize(request.userIds(), request.departments());
            groupSources.validate(draft, previous, false);
            return recipients(draft, request.page());
        });
    }

    /**
     * 分页获取指定群组的场景引用。
     */
    public Page<SceneOption> usages(Long id, Query query) {
        return snapshot.read(() -> {
            requireGroup(id, false);
            return store.pageUsages(id, query);
        });
    }

    /**
     * 按名称或账号分页搜索启用账号。
     */
    public Page<UserOption> userCandidates(Query query) {
        return snapshot.read(() -> store.pageUserCandidates(query));
    }

    /**
     * 返回可配置的启用部门。
     */
    public List<DepartmentOption> departmentCandidates() {
        return store.departments().candidates();
    }

    /**
     * 分页搜索可绑定的启用群组。
     */
    public Page<GroupOption> options(Query query) {
        return snapshot.read(() -> store.pageOptions(query));
    }

    /**
     * 按场景编码分页获取已登记的场景。
     */
    public Page<SceneListItem> scenes(Query query) {
        return snapshot.read(() -> store.pageScenes(query));
    }

    /**
     * 读取场景版本和整个绑定集合。
     */
    public SceneDetail sceneDetail(String sceneCode) {
        return snapshot.read(() -> sceneDetail(requireScene(sceneCode, false)));
    }

    private void saveGroupConfiguration(GroupRow group, String name, String description, Sources sources,
                                        boolean nameChanged, boolean usersChanged, boolean departmentsChanged) {
        if (nameChanged) {
            requireAvailableName(name, group.id());
        }
        try {
            store.updateGroup(group.id(), name, description, actorId(), now());
        } catch (DuplicateKeyException error) {
            throw NotificationGroupFailure.nameConflict();
        }
        if (usersChanged) {
            store.replaceUsers(group.id(), sources.users());
        }
        if (departmentsChanged) {
            store.replaceDepartments(group.id(), sources.departments());
        }
    }

    private GroupDetail detail(GroupRow group) {
        var departments = groupSources.departmentDetails(group.id());
        return new GroupDetail(group.id(), group.name(), group.description(), group.enabled(), group.version(),
                group.createdBy(), group.updatedBy(), group.createdAt().atOffset(ZoneOffset.UTC),
                group.updatedAt().atOffset(ZoneOffset.UTC), store.groupUsers(group.id()), departments);
    }

    SceneDetail sceneDetail(SceneRow scene) {
        var groups = store.groups(store.groupIds(scene.sceneCode())).stream()
                .map(row -> new GroupOption(row.id(), row.name())).toList();
        return new SceneDetail(scene.sceneCode(), scene.nameCn(), scene.nameEn(), scene.version(), groups,
                scene.sceneType(), scene.enabled(), scene.titleTemplate(), scene.summaryTemplate(), scene.mailTemplateId());
    }

    private Page<UserOption> recipients(Sources sources, Query query) {
        return store.pageRecipients(snapshot.recipients(sources.users(), sources.departments()), query);
    }

    private GroupRow requireGroup(Long id, boolean lock) {
        if (id == null || id <= 0) {
            throw NotificationGroupFailure.invalidInput();
        }
        GroupRow row = store.group(id, lock);
        if (row == null) {
            throw NotificationGroupFailure.missingGroup();
        }
        return row;
    }

    private SceneRow requireScene(String code, boolean lock) {
        if (!validSceneCode(code)) {
            throw NotificationGroupFailure.invalidInput();
        }
        SceneRow row = store.scene(code, lock);
        if (row == null) {
            throw NotificationGroupFailure.missingScene();
        }
        return row;
    }

    private void requireAvailableName(String name, Long currentId) {
        Long other = store.groupIdForName(name);
        if (other != null && !other.equals(currentId)) {
            throw NotificationGroupFailure.nameConflict();
        }
    }

    private static void requireVersion(long currentVersion, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw NotificationGroupFailure.invalidInput();
        }
        if (expectedVersion != currentVersion) {
            throw NotificationGroupFailure.versionConflict();
        }
    }

    private static String normalizedName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 100) {
            throw NotificationGroupFailure.invalidInput();
        }
        return value;
    }

    private static String normalizedDescription(String description) {
        String value = description == null ? "" : description.strip();
        if (value.codePointCount(0, value.length()) > 500) {
            throw NotificationGroupFailure.invalidInput();
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * 对所有旧选项和新选项按固定顺序做主键锁读。逐个主键锁定至多 40 行，
     * 不依赖数据库对批量 IN + ORDER BY 的优化及锁顺序。
     */
    private void lockAndValidateGroups(List<Long> current, List<Long> desired) {
        var union = new TreeSet<>(current);
        union.addAll(desired);
        var locked = new HashMap<Long, GroupRow>();
        for (Long id : union) {
            locked.put(id, store.group(id, true));
        }
        for (Long id : desired) {
            GroupRow group = locked.get(id);
            if (group == null || !group.enabled()) {
                throw NotificationGroupFailure.badRequest("invalid-group");
            }
        }
    }

    /**
     * 由可信审计身份填充操作人；显式系统身份没有账号，主表用保留标识 0，真实名称由审计保存。
     */
    private int actorId() {
        var actor = auditContext.currentActor();
        return switch (actor.type()) {
            case "SYSTEM" -> 0;
            case "USER" -> Integer.parseInt(actor.id());
            default -> throw new IllegalStateException("保存通知群组需要已认证的操作人");
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static void logGroup(String action, GroupDetail detail, boolean changed, long start) {
        String requestId = AuditParticipants.currentRequestId();
        Long id = detail.id();
        long version = detail.version();
        boolean enabled = detail.enabled();
        int userCount = detail.users().size();
        int departmentCount = detail.departments().size();
        afterCommit(() -> log.info("通知群组操作已提交，action={} groupId={} version={} enabled={} userCount={} departmentCount={} changed={} requestId={} elapsedMs={}",
                action, id, version, enabled, userCount, departmentCount, changed, requestId, elapsedMs(start)));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
