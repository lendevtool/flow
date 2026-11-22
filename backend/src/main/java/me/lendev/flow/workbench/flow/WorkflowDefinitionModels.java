package me.lendev.flow.workbench.flow;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

import me.lendev.flow.field.FieldModels;
import me.lendev.flow.workbench.flow.FlowModels.DefinitionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.StringNode;

/**
 * 两级角色流程的模板配置与字段快照。
 */
final class WorkflowDefinitionModels {

    private WorkflowDefinitionModels() {
    }

    /**
     * 节点办理模式：主办单办、广播抢办、单角色会签、多角色会签。
     */
    enum Mode {
        PRIMARY, BROADCAST, ALL, MULTI_ALL;

        boolean countersign() {
            return this == ALL || this == MULTI_ALL;
        }
    }

    /**
     * 模板节点配置。parentKey 指向复用来源节点；MULTI_ALL 使用 roleCodes，
     * 其余模式使用 roleCode；editableFields/requiredFields 约束字段提交。
     * 通知编码在请求中用 Java null 表示省略，用 JSON null 表示解除引用。
     */
    record Node(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String key,
            @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String parentKey,
            @Min(1) @Max(100) int order,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 50) String roleCode,
            @Size(max = 100) List<@NotBlank @Size(max = 50) String> roleCodes,
            Mode mode,
            @Size(max = 100) List<@NotBlank @Size(max = 64) String> editableFields,
            @Size(max = 100) List<@NotBlank @Size(max = 64) String> requiredFields,
            @Min(0) @Max(3650) Integer dueOffsetDays,
            @Schema(type = "string", nullable = true) @JsonInclude(JsonInclude.Include.NON_NULL)
            JsonNode onApprovedSceneCode,
            @Schema(type = "string", nullable = true) @JsonInclude(JsonInclude.Include.NON_NULL)
            JsonNode onRejectedSceneCode) {

        List<String> handlingRoles() {
            if (mode == Mode.MULTI_ALL) {
                return roleCodes == null ? List.of() : roleCodes;
            }
            return roleCode == null ? List.of() : List.of(roleCode);
        }

        boolean countersign() {
            return mode != null && mode.countersign();
        }

        Node withScenes(String approved, String rejected) {
            return notices(approved == null ? NullNode.getInstance() : StringNode.valueOf(approved),
                    rejected == null ? NullNode.getInstance() : StringNode.valueOf(rejected));
        }

        Node withoutScenes() {
            return notices(null, null);
        }

        String sceneCode(Trigger trigger) {
            var value = sceneInput(trigger);
            return value == null || value.isNull() ? null : value.asString();
        }

        JsonNode sceneInput(Trigger trigger) {
            return trigger == Trigger.APPROVED ? onApprovedSceneCode : onRejectedSceneCode;
        }

        private Node notices(JsonNode approved, JsonNode rejected) {
            return new Node(key, parentKey, order, title, roleCode, roleCodes, mode, editableFields, requiredFields,
                    dueOffsetDays, approved, rejected);
        }
    }

    /**
     * 关联表中的流程节点通知触发动作。
     */
    enum Trigger {
        APPROVED, REJECTED
    }

    /**
     * 字段绑定快照，config 为空表示仅引用字段编码。
     */
    record FieldBinding(String code, FieldModels.Config config, List<FieldModels.Option> options) {

        FieldBinding(String code) {
            this(code, null, List.of());
        }
    }

    /**
     * 模板写入请求。version 为 null 表示新建，编辑时必填。复制已发布版本时可指定 sourceDefinitionId。
     */
    record Write(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]*") String code,
            @NotBlank @Size(max = 200) String name,
            @PositiveOrZero Long version,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 64) String> fields,
            @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull Node> nodes,
            @Positive Long sourceDefinitionId) {

    }

    /**
     * 主办配置校验请求，仅携带节点列表。
     */
    record PrimaryHandlerCheck(@NotNull @Size(min = 1, max = 100) List<@Valid @NotNull Node> nodes) {
    }

    /**
     * 模板完整视图，含字段绑定与节点配置。
     */
    record View(long id, String code, int revision, String name, DefinitionStatus status, long version,
                List<FieldBinding> fields, List<Node> nodes, LocalDateTime publishedAt,
                List<NotificationWarning> notificationWarnings) {

        View(long id, String code, int revision, String name, DefinitionStatus status, long version,
             List<FieldBinding> fields, List<Node> nodes, LocalDateTime publishedAt) {
            this(id, code, revision, name, status, version, fields, nodes, publishedAt, List.of());
        }

        View withNotificationWarnings(List<NotificationWarning> warnings) {
            return new View(id, code, revision, name, status, version, fields, nodes, publishedAt, warnings);
        }
    }

    /**
     * 模板可引用的启用流程附加场景。
     */
    record NotificationSceneOption(String sceneCode, String nameCn, String nameEn) {
    }

    /**
     * 发布时提醒未绑定通知群组的节点动作，允许发布。
     */
    record NotificationWarning(String nodeKey, Trigger triggerType, String sceneCode, String reason) {
    }

    /**
     * 可选角色，hasEnabledMembers 表示是否有启用成员。
     */
    record RoleOption(String code, String name, String nameEn, boolean hasEnabledMembers) {

    }

    /**
     * 模板变更前后视图，供审计使用。
     */
    record Change(View before, View after) {

    }
}
