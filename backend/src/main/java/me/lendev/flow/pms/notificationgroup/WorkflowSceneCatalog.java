package me.lendev.flow.pms.notificationgroup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.pms.notificationgroup.mapper.WorkflowSceneMapper;
import me.lendev.flow.pms.notificationgroup.mapper.WorkflowSceneMapper.MailTemplate;
import org.springframework.stereotype.Service;

/**
 * 流程模板和投递器共用的通知场景配置入口。
 */
@Service
@RequiredArgsConstructor
public class WorkflowSceneCatalog {

    public static final String EXTRA = "WORKFLOW_EXTRA";
    public static final String SYSTEM = "WORKFLOW_SYSTEM";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[ \\t]*([A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)*+)[ \\t]*}}");
    private static final Set<String> SITE_VARIABLES = Set.of("event.type", "node.name", "targetNode.name");
    private static final Set<String> MAIL_VARIABLES = Set.of("event.type", "node.name", "targetNode.name", "recipient.name");
    private final WorkflowSceneMapper scenes;
    private final NotificationReadSnapshot snapshot;
    private final NotificationRecipientResolver recipients;

    /**
     * 读取并锁定场景。调用方须已开启写事务，并按场景编码升序锁定多个场景。
     */
    public Scene lock(String sceneCode) {
        return scenes.scene(sceneCode, true);
    }

    /**
     * 在一次数据库快照中读取场景设置和群组接收人。
     */
    public PreparedScene prepare(String sceneCode) {
        return snapshot.read(() -> {
            Scene scene = scenes.scene(sceneCode, false);
            return new PreparedScene(scene, scene == null || !scene.enabled() || !EXTRA.equals(scene.type())
                    ? null : recipients.resolve(sceneCode));
        });
    }

    /**
     * 返回可供模板引用的启用附加场景。
     */
    public List<Scene> enabledExtras() {
        return scenes.enabledExtras();
    }

    /**
     * 场景是否配置了至少一个通知群组。
     */
    public boolean hasGroups(String sceneCode) {
        return scenes.groupCount(sceneCode) > 0;
    }

    /**
     * 校验已启用的附加场景标题和模板配置，供发布校验使用。
     */
    public boolean usable(Scene scene) {
        return scene != null && scene.enabled() && EXTRA.equals(scene.type()) && validSite(scene.titleTemplate(), scene.summaryTemplate())
                && (scene.mailTemplateId() == null || usableMail(scenes.mailTemplate(scene.mailTemplateId())));
    }

    static boolean validSite(String title, String summary) {
        return title != null && !title.isBlank() && supported(title, SITE_VARIABLES) && supported(summary, SITE_VARIABLES);
    }

    static boolean usableMail(MailTemplate template) {
        return template != null && template.enable() && supported(template.subjectTemplate(), MAIL_VARIABLES)
                && supported(template.bodyTemplate(), MAIL_VARIABLES);
    }

    private static boolean supported(String template, Set<String> allowed) {
        if (template == null) {
            return true;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                return false;
            }
        }
        String remainder = matcher.replaceAll("");
        return !remainder.contains("{{") && !remainder.contains("}}");
    }

    /**
     * 渲染事先通过校验的站内模板，不解析 HTML 或表达式。
     */
    public static String render(String template, Map<String, String> variables) {
        if (template == null) {
            return null;
        }
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(variables.getOrDefault(match.group(1), "")));
    }

    /**
     * 场景配置及其乐观锁版本和启用周期。
     */
    public record Scene(String sceneCode, String nameCn, String nameEn, String type, boolean enabled, long version,
                        long activationVersion, String titleTemplate, String summaryTemplate, Long mailTemplateId) {
    }

    /**
     * 场景及同一快照中的群组解析结果。无有效流程附加场景时解析结果为空。
     */
    public record PreparedScene(Scene scene, NotificationRecipientResolver.Result recipients) {
    }
}
