package me.lendev.flow.pms.notificationgroup;

import static me.lendev.flow.pms.notificationgroup.NotificationGroupFailure.*;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.validSceneCode;
import static me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.EXTRA;
import static me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.SYSTEM;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditContext;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.SceneDetail;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.Scene;
import me.lendev.flow.pms.notificationgroup.mapper.WorkflowSceneMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 管理附加通知场景及系统办理提醒的可选邮件配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSceneService {

    private static final String INVALID_SCENE = "workflow-scene-invalid";

    private final WorkflowSceneMapper scenes;
    private final NotificationGroupStore store;
    private final NotificationGroupService groups;
    private final NotificationReadSnapshot snapshot;
    private final AuditContext audit;
    private final Clock clock;

    /**
     * 新建可供流程节点引用的附加场景，场景编码创建后固定。
     */
    @Audited(AuditOperation.WORKFLOW_NOTIFICATION_SCENE_CREATE)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SceneDetail create(Write request) {
        String code = request.sceneCode();
        if (!validSceneCode(code)) {
            throw invalidInput();
        }
        Scene scene = extra(code, request, !Boolean.FALSE.equals(request.enabled()), 0, 0);
        requireMail(scene.mailTemplateId());
        try {
            scenes.insert(scene, actorId(), now());
        } catch (DuplicateKeyException conflict) {
            throw sceneCodeConflict();
        }
        logSaved(scene, true);
        return detail(code);
    }

    /**
     * 使用与群组绑定共用的版本检查保存配置，重新启用时开启新的投递周期。
     */
    @Audited(AuditOperation.WORKFLOW_NOTIFICATION_SCENE_UPDATE)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SceneDetail update(String code, Write request) {
        if (request.sceneCode() != null && !code.equals(request.sceneCode())) {
            throw invalidInput();
        }
        Scene previous = requireScene(code, true);
        if (request.expectedVersion() == null) {
            throw invalidInput();
        }
        if (previous.version() != request.expectedVersion()) {
            throw versionConflict();
        }
        Scene candidate = changes(previous, request);
        if (!Objects.equals(previous.mailTemplateId(), candidate.mailTemplateId())
                || (!previous.enabled() && candidate.enabled())) {
            requireMail(candidate.mailTemplateId());
        }
        if (previous.equals(candidate)) {
            logSaved(previous, false);
            return detail(code);
        }
        long version = previous.version() + 1;
        long activation = !previous.enabled() && candidate.enabled() ? version : previous.activationVersion();
        Scene saved = new Scene(code, candidate.nameCn(), candidate.nameEn(), candidate.type(), candidate.enabled(),
                version, activation, candidate.titleTemplate(), candidate.summaryTemplate(), candidate.mailTemplateId());
        if (scenes.update(saved, previous.version(), actorId(), now()) != 1) {
            throw versionConflict();
        }
        logSaved(saved, true);
        return detail(code);
    }

    /**
     * 在同一只读快照统计已发布的引用版本和未结束实例。
     */
    public Usages usages(String code) {
        return snapshot.read(() -> {
            requireScene(code, false);
            return new Usages(scenes.publishedDefinitionCount(code), scenes.runningInstanceCount(code));
        });
    }

    /**
     * 返回模板标识和可用性，供配置页选择，隐藏邮件模板内容及账户信息。
     */
    public List<MailOption> mailTemplates() {
        return scenes.mailTemplates().stream()
                .map(t -> new MailOption(t.id(), t.code(), t.name(), WorkflowSceneCatalog.usableMail(t))).toList();
    }

    private Scene changes(Scene scene, Write request) {
        if (EXTRA.equals(scene.type())) {
            boolean enabled = request.enabled() == null ? scene.enabled() : request.enabled();
            return extra(scene.sceneCode(), request, enabled, scene.version(), scene.activationVersion());
        }
        if (!SYSTEM.equals(scene.type()) || Boolean.FALSE.equals(request.enabled())) {
            throw badRequest(INVALID_SCENE);
        }
        return new Scene(scene.sceneCode(), scene.nameCn(), scene.nameEn(), SYSTEM, true, scene.version(),
                scene.activationVersion(), scene.titleTemplate(), scene.summaryTemplate(), request.mailTemplateId());
    }

    private static Scene extra(String code, Write request, boolean enabled, long version, long activation) {
        String name = normalized(request.nameCn(), 100);
        String nameEn = normalized(request.nameEn(), 100);
        String title = normalized(request.titleTemplate(), 200);
        String summary = normalized(request.summaryTemplate(), 1000);
        if (name == null || !WorkflowSceneCatalog.validSite(title, summary)) {
            throw badRequest(INVALID_SCENE);
        }
        return new Scene(code, name, nameEn, EXTRA, enabled, version, activation, title, summary,
                request.mailTemplateId());
    }

    private static String normalized(String value, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > limit) {
            throw badRequest(INVALID_SCENE);
        }
        return trimmed;
    }

    private void requireMail(Long id) {
        if (id != null && (id <= 0 || !WorkflowSceneCatalog.usableMail(scenes.mailTemplate(id)))) {
            throw badRequest("workflow-scene-mail-template");
        }
    }

    private Scene requireScene(String code, boolean lock) {
        if (!validSceneCode(code)) {
            throw invalidInput();
        }
        Scene scene = scenes.scene(code, lock);
        if (scene == null) {
            throw missingScene();
        }
        return scene;
    }

    private SceneDetail detail(String code) {
        return groups.sceneDetail(store.scene(code, false));
    }

    private int actorId() {
        var actor = audit.currentActor();
        return switch (actor.type()) {
            case "SYSTEM" -> 0;
            case "USER" -> Integer.parseInt(actor.id());
            default -> throw new IllegalStateException("保存流程通知场景需要已认证的操作人");
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void logSaved(Scene scene, boolean changed) {
        String requestId = AuditParticipants.currentRequestId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("流程通知场景操作已提交，sceneCode={} sceneType={} version={} enabled={} changed={} requestId={}",
                        scene.sceneCode(), scene.type(), scene.version(), scene.enabled(), changed, requestId);
            }
        });
    }

    /**
     * 创建或编辑表单，更新须携带所读版本。
     */
    public record Write(String sceneCode, String nameCn, String nameEn, String titleTemplate, String summaryTemplate,
                        Boolean enabled, @Positive Long mailTemplateId, @PositiveOrZero Long expectedVersion) {
    }

    /**
     * 停用前展示的影响范围。
     */
    public record Usages(long publishedDefinitionCount, long runningInstanceCount) {
    }

    /**
     * 邮件模板选择项。
     */
    public record MailOption(long id, String code, String name, boolean enable) {
    }
}
