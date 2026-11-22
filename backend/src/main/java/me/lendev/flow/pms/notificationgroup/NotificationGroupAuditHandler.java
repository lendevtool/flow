package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupFailure.invalidInput;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupFailure.missingGroup;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupFailure.missingScene;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.validSceneCode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.CreateGroupRequest;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.DepartmentSelection;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.GroupDetail;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.GroupStatusRequest;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.ReplaceSceneGroupsRequest;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.SceneDetail;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.UpdateGroupRequest;
import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.GroupRow;
import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.SceneRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 通知群组配置与通知场景绑定的审计处理器，从业务事务中读取实际存储值。
 */
@Component
@RequiredArgsConstructor
public class NotificationGroupAuditHandler implements AuditHandler {

    private final NotificationGroupStore store;
    private final AuditSnapshots snapshots;
    private final NotificationTransactionConnection connection;

    /**
     * @return 通知群组及通知场景配置的数据库写入操作
     */
    @Override
    public Set<AuditOperation> operations() {
        return EnumSet.of(AuditOperation.NOTIFICATION_GROUP_CREATE, AuditOperation.NOTIFICATION_GROUP_UPDATE,
                AuditOperation.NOTIFICATION_GROUP_STATUS_UPDATE, AuditOperation.NOTIFICATION_SCENE_GROUPS_REPLACE);
    }

    /**
     * 校验受审计入口的参数位置与返回类型，避免运行期误读对象标识。
     *
     * @param method 受审计的业务入口
     * @throws IllegalStateException 入口的操作、参数或返回类型与注册不一致
     */
    @Override
    public void validate(Method method) {
        AuditOperation operation = method.getAnnotation(Audited.class).value();
        Class<?>[] parameters = switch (operation) {
            case NOTIFICATION_GROUP_CREATE -> new Class<?>[]{CreateGroupRequest.class};
            case NOTIFICATION_GROUP_UPDATE -> new Class<?>[]{Long.class, UpdateGroupRequest.class};
            case NOTIFICATION_GROUP_STATUS_UPDATE -> new Class<?>[]{Long.class, GroupStatusRequest.class};
            case NOTIFICATION_SCENE_GROUPS_REPLACE -> new Class<?>[]{String.class, ReplaceSceneGroupsRequest.class};
            default -> throw new IllegalStateException("通知群组审计操作未绑定");
        };
        Class<?> returnType = operation == AuditOperation.NOTIFICATION_SCENE_GROUPS_REPLACE
                ? SceneDetail.class : GroupDetail.class;
        if (!Arrays.equals(parameters, method.getParameterTypes()) || method.getReturnType() != returnType) {
            throw new IllegalStateException("通知群组审计方法签名与注册定义不一致: " + method.getName());
        }
    }

    /**
     * 在业务写入前锁定群组或场景的主行并固定快照。新建在业务返回实际标识后采集首次快照。
     *
     * @param operation 本次操作
     * @param args      可用于识别目标的业务入口参数
     * @return 在同一业务事务中重读保存结果的计划
     */
    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        connection.prepare(TransactionAspectSupport.currentTransactionStatus().isNewTransaction());
        return switch (operation) {
            case NOTIFICATION_GROUP_CREATE ->
                    result -> List.of(snapshots.created(groupCapture(((GroupDetail) result).id())));
            case NOTIFICATION_GROUP_UPDATE, NOTIFICATION_GROUP_STATUS_UPDATE -> prepareGroupUpdate(args[0]);
            case NOTIFICATION_SCENE_GROUPS_REPLACE -> prepareSceneUpdate(args[0]);
            default -> throw new IllegalStateException("通知群组审计操作未绑定");
        };
    }

    private Plan prepareGroupUpdate(Object target) {
        if (!(target instanceof Long id) || id <= 0) {
            throw invalidInput();
        }
        var before = groupCapture(id);
        return result -> List.of(snapshots.updated(before, groupCapture(((GroupDetail) result).id())));
    }

    private AuditSnapshots.Capture groupCapture(long id) {
        var group = requireGroup(id);
        var departments = store.departments(id).stream()
                .sorted(Comparator.comparingLong(DepartmentSelection::departmentId))
                .map(item -> Map.of("departmentId", item.departmentId().toString(),
                        "includeDescendants", item.includeDescendants()))
                .toList();
        return snapshots.capture(groupObject(group), Map.of(
                "name", AuditField.visible("名称", group.name()),
                "description", AuditField.visible("说明", group.description()),
                "enabled", AuditField.visible("启用", group.enabled()),
                "userIds", AuditField.visible("指定用户", idStrings(store.users(id))),
                "departments", AuditField.visible("部门范围", departments)));
    }

    /**
     * 先锁场景行并固定旧绑定，由业务按升序锁定旧、新绑定涉及的群组。保存后仅为新增和移除的群组生成引用。
     */
    private Plan prepareSceneUpdate(Object target) {
        if (!(target instanceof String code) || !validSceneCode(code)) {
            throw invalidInput();
        }
        var scene = requireScene(code);
        var beforeIds = List.copyOf(store.groupIds(code));
        var before = sceneCapture(scene, beforeIds);
        return result -> {
            if (!code.equals(((SceneDetail) result).sceneCode())) {
                throw new IllegalStateException("通知场景审计前后编码不一致");
            }
            var saved = requireScene(code);
            var afterIds = store.groupIds(code);
            var changes = new ArrayList<AuditChange>();
            changes.add(snapshots.updated(before, sceneCapture(saved, afterIds)));
            var changedIds = new TreeSet<>(beforeIds);
            changedIds.addAll(afterIds);
            changedIds.removeIf(id -> beforeIds.contains(id) && afterIds.contains(id));
            var relatedGroups = store.groups(List.copyOf(changedIds));
            if (relatedGroups.size() != changedIds.size()) {
                throw new IllegalStateException("通知场景审计引用的群组缺失");
            }
            for (var group : relatedGroups) {
                changes.add(snapshots.reference(groupObject(group)));
            }
            return changes;
        };
    }

    private AuditSnapshots.Capture sceneCapture(SceneRow scene, List<Long> groupIds) {
        var object = new AuditObject("NOTIFICATION_SCENE", scene.sceneCode(), scene.sceneCode(), scene.nameCn(), null, null);
        return snapshots.capture(object, Map.of("groupIds", AuditField.visible("绑定群组", idStrings(groupIds))));
    }

    private GroupRow requireGroup(long id) {
        var group = store.group(id, true);
        if (group == null) {
            throw missingGroup();
        }
        return group;
    }

    private SceneRow requireScene(String code) {
        var scene = store.scene(code, true);
        if (scene == null) {
            throw missingScene();
        }
        return scene;
    }

    private static AuditObject groupObject(GroupRow group) {
        return new AuditObject("NOTIFICATION_GROUP", Long.toString(group.id()), null, group.name(), null, null);
    }

    private static List<String> idStrings(List<Long> ids) {
        return ids.stream().distinct().sorted().map(Object::toString).toList();
    }
}
