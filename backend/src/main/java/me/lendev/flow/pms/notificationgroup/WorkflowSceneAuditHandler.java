package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.validSceneCode;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.SceneDetail;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.Scene;
import me.lendev.flow.pms.notificationgroup.mapper.WorkflowSceneMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 在配置写事务内采集流程通知场景的审计差异，模板内容以脱敏字段参与比较。
 */
@Component
@RequiredArgsConstructor
public class WorkflowSceneAuditHandler implements AuditHandler {

    private final WorkflowSceneMapper scenes;
    private final AuditSnapshots snapshots;
    private final NotificationTransactionConnection connection;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.WORKFLOW_NOTIFICATION_SCENE_CREATE, AuditOperation.WORKFLOW_NOTIFICATION_SCENE_UPDATE);
    }

    @Override
    public void validate(Method method) {
        Class<?>[] expected = method.getAnnotation(Audited.class).value() == AuditOperation.WORKFLOW_NOTIFICATION_SCENE_CREATE
                ? new Class<?>[]{WorkflowSceneService.Write.class}
                : new Class<?>[]{String.class, WorkflowSceneService.Write.class};
        if (method.getReturnType() != SceneDetail.class || !Arrays.equals(expected, method.getParameterTypes())) {
            throw new IllegalStateException("流程通知场景审计方法签名不匹配: " + method.getName());
        }
    }

    /**
     * 更新前锁住场景行并固定旧值，新建从业务返回值确定编码。
     */
    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        connection.prepare(TransactionAspectSupport.currentTransactionStatus().isNewTransaction());
        if (operation == AuditOperation.WORKFLOW_NOTIFICATION_SCENE_CREATE) {
            return result -> List.of(snapshots.created(capture(((SceneDetail) result).sceneCode())));
        }
        if (!(args[0] instanceof String code) || !validSceneCode(code)) {
            throw NotificationGroupFailure.invalidInput();
        }
        var before = capture(code);
        return result -> List.of(snapshots.updated(before, capture(code)));
    }

    private AuditSnapshots.Capture capture(String code) {
        Scene scene = scenes.scene(code, true);
        if (scene == null) {
            throw NotificationGroupFailure.missingScene();
        }
        var object = new AuditObject("NOTIFICATION_SCENE", code, code, scene.nameCn(), null, null);
        return snapshots.capture(object, Map.of(
                "nameCn", AuditField.visible("中文名称", scene.nameCn()),
                "nameEn", AuditField.visible("英文名称", scene.nameEn()),
                "enabled", AuditField.visible("启用", scene.enabled()),
                "version", AuditField.visible("配置版本", scene.version()),
                "mailTemplateId", AuditField.visible("邮件模板 ID", scene.mailTemplateId()),
                "titleTemplate", AuditField.hidden("站内标题模板", scene.titleTemplate()),
                "summaryTemplate", AuditField.hidden("站内摘要模板", scene.summaryTemplate())));
    }
}
