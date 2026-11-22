package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.pms.notificationgroup.NotificationGroupModels.validSceneCode;
import static me.lendev.flow.workbench.flow.WorkflowDefinitionModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.Scene;
import org.springframework.stereotype.Component;

/**
 * 处理草稿的通知字段合并、场景校验与发布提示。
 */
@Component
@RequiredArgsConstructor
class WorkflowDefinitionNotices {

    private final WorkflowSceneCatalog catalog;

    List<NotificationSceneOption> options() {
        return catalog.enabledExtras().stream()
                .map(scene -> new NotificationSceneOption(scene.sceneCode(), scene.nameCn(), scene.nameEn())).toList();
    }

    /**
     * 省略字段时继承同键节点引用，显式传空时解除。节点自身结构已经过校验。
     */
    List<Node> merge(List<Node> requested, List<Node> previous) {
        var previousByKey = previous.stream().collect(Collectors.toMap(Node::key, Function.identity()));
        return requested.stream().map(node -> {
            var before = previousByKey.get(node.key());
            return node.withScenes(resolve(node, before, Trigger.APPROVED), resolve(node, before, Trigger.REJECTED));
        }).toList();
    }

    private static String resolve(Node requested, Node previous, Trigger trigger) {
        var input = requested.sceneInput(trigger);
        if (input == null) {
            return previous == null ? null : previous.sceneCode(trigger);
        }
        if (input.isNull()) {
            return null;
        }
        if (!input.isString() || !validSceneCode(input.asString())) {
            throw localized(sceneError(trigger), requested.title());
        }
        return input.asString();
    }

    /**
     * 按编码升序锁定唯一场景，检查启用状态及引用类型。发布时检查渠道配置并按节点动作给出空群组提示。
     */
    List<NotificationWarning> validate(List<Node> nodes, boolean publishing) {
        var refs = references(nodes);
        var codes = refs.stream().map(NodeScene::code).collect(Collectors.toCollection(TreeSet::new));
        Set<String> available = codes.stream().map(catalog::lock)
                .filter(scene -> scene != null && scene.enabled() && WorkflowSceneCatalog.EXTRA.equals(scene.type()))
                .filter(scene -> !publishing || catalog.usable(scene)).map(Scene::sceneCode)
                .collect(Collectors.toSet());
        for (var ref : refs) {
            if (!available.contains(ref.code())) {
                throw localized(sceneError(ref.trigger()), ref.node().title());
            }
        }
        if (!publishing) {
            return List.of();
        }
        return warnings(refs, codes);
    }

    /**
     * 为草稿详情预览空群组提示，发布时会在获取场景锁后重验。
     */
    List<NotificationWarning> preview(List<Node> nodes) {
        var refs = references(nodes);
        return warnings(refs, refs.stream().map(NodeScene::code).collect(Collectors.toSet()));
    }

    private List<NotificationWarning> warnings(List<NodeScene> refs, Set<String> codes) {
        var noGroups = codes.stream().filter(code -> !catalog.hasGroups(code)).collect(Collectors.toSet());
        return refs.stream().filter(ref -> noGroups.contains(ref.code()))
                .map(ref -> new NotificationWarning(ref.node().key(), ref.trigger(), ref.code(), "NO_GROUPS")).toList();
    }

    private static List<NodeScene> references(List<Node> nodes) {
        var parentKeys = nodes.stream().map(Node::parentKey).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var refs = new ArrayList<NodeScene>();
        for (var node : nodes) {
            String approved = node.sceneCode(Trigger.APPROVED);
            String rejected = node.sceneCode(Trigger.REJECTED);
            if (approved != null) {
                refs.add(new NodeScene(node, Trigger.APPROVED, approved));
            }
            if (rejected != null) {
                if (parentKeys.contains(node.key())) {
                    throw localized("error.workflow.notice.summary", node.title());
                }
                refs.add(new NodeScene(node, Trigger.REJECTED, rejected));
            }
        }
        return refs;
    }

    private static String sceneError(Trigger trigger) {
        return trigger == Trigger.APPROVED ? "error.workflow.notice.approved" : "error.workflow.notice.rejected";
    }

    private record NodeScene(Node node, Trigger trigger, String code) {
    }
}
