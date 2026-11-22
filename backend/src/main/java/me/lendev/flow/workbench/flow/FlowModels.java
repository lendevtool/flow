package me.lendev.flow.workbench.flow;

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
import java.util.Map;
import java.util.function.IntConsumer;

import me.lendev.flow.workbench.todo.TodoModels.Source;

/**
 * 固定步骤流程的传输模型及内部状态。
 */
public final class FlowModels {

    private FlowModels() {
    }

    /**
     * 定义状态：草稿、已发布、已停用。
     */
    public enum DefinitionStatus {DRAFT, PUBLISHED, RETIRED}

    /**
     * 运行状态：进行中、已完成、已取消。
     */
    public enum RunStatus {RUNNING, DONE, CANCELLED}

    /**
     * 步骤状态：未激活、办理中、已完成、已取消。
     */
    public enum StepStatus {WAITING, ACTIVE, DONE, CANCELLED}

    /**
     * 固定步骤配置，dueOffsetDays 为相对激活日的截止偏移。
     */
    public record Step(@Positive int stepNo, @NotBlank @Size(max = 200) String title,
                       @Positive Integer assigneeUserId, @Min(0) @Max(3650) Integer dueOffsetDays) {

    }

    /**
     * 创建定义请求，code 全局唯一，步骤数 1-30。
     */
    public record CreateDefinition(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}") String code,
                                   @NotBlank @Size(max = 200) String name,
                                   @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid Step> steps) {

    }

    /**
     * 编辑定义请求，须携带当前乐观锁版本。
     */
    public record EditDefinition(@NotBlank @Size(max = 200) String name,
                                 @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid Step> steps,
                                 @NotNull @PositiveOrZero Long version) {

    }

    /**
     * 定义视图，revision 为同一编码下的修订号。
     */
    public record Definition(long id, String code, int revision, String name, DefinitionStatus status,
                             List<Step> steps, long version) {

    }

    /**
     * 发起流程请求。requestKey 为幂等键，assignees 按步骤号指定负责人，
     * dimensions 为公共维度槽位取值。
     */
    public record Start(@NotBlank @Size(max = 128) String requestKey, @Positive long definitionId,
                        @NotBlank @Size(max = 200) String title,
                        @Size(max = 30) Map<@Positive Integer, @NotNull @Positive Integer> assignees,
                        @Size(max = 5) Map<@Min(1) @Max(5) Integer, @Positive Long> dimensions) {

    }

    /**
     * 同应用业务调用获得稳定的流程标识及当前状态。
     */
    public record Started(long id, RunStatus status, long version) {

    }

    /**
     * 仅携带乐观锁版本的请求。
     */
    public record Version(@NotNull @PositiveOrZero Long version) {

    }

    /**
     * 完成步骤请求，todoId 定位节点待办。
     */
    public record Complete(@Positive long todoId, @NotNull @PositiveOrZero Long version) {

    }

    /**
     * 取消流程请求，须填写原因。
     */
    public record Cancel(@NotBlank @Size(max = 500) String reason, @NotNull @PositiveOrZero Long version) {

    }

    /**
     * 改派步骤负责人请求。
     */
    public record Assign(@Positive int stepNo, @Positive int assigneeUserId, @NotNull @PositiveOrZero Long version) {

    }

    /**
     * 流程进度视图，allowedActions 按当前查看人计算。
     */
    public record Progress(long id, long definitionId, String title, Integer ownerUserId, String ownerName,
                           RunStatus status, int currentStepNo, long version, List<StepView> steps,
                           List<String> allowedActions) {

    }

    /**
     * 步骤展示视图，todoId 为激活后生成的节点待办。
     */
    public record StepView(int stepNo, String title, Integer assigneeUserId, String assigneeName,
                           Integer dueOffsetDays, Long todoId, StepStatus status) {

    }

    /**
     * 步骤内部状态。activationKey 为节点待办的幂等激活标识。
     */
    record Node(int stepNo, String title, int assigneeUserId, String assigneeName, Integer dueOffsetDays,
                String activationKey, Long todoId, StepStatus status, String cancelReason) {

        Node activated(String key, long id, String activeName) {
            return new Node(stepNo, title, assigneeUserId, activeName, dueOffsetDays, key, id, StepStatus.ACTIVE, null);
        }

        Node closed(StepStatus target, String reason) {
            return new Node(stepNo, title, assigneeUserId, assigneeName, dueOffsetDays, activationKey, todoId,
                    target, reason);
        }

        Node assigned(int userId, String name) {
            return new Node(stepNo, title, userId, name, dueOffsetDays, activationKey, todoId, status, cancelReason);
        }
    }

    /**
     * 流程运行内部状态，changed 生成版本递增的新状态。
     */
    record Run(long id, long definitionId, String title, Integer ownerUserId, String ownerName, Source source,
               Map<Integer, Long> dimensions, List<Node> steps, int currentStepNo, RunStatus status,
               long version, LocalDateTime closedAt) {

        Node current() {
            return steps.get(currentStepNo - 1);
        }

        Run changed(List<Node> changed, int current, RunStatus next, LocalDateTime closed) {
            return new Run(id, definitionId, title, ownerUserId, ownerName, source, dimensions, List.copyOf(changed),
                    current, next, version + 1, closed);
        }
    }

    /**
     * 发起上下文。authorize 为业务来源的附加授权回调。
     */
    record Launch(Start request, Integer owner, Source source, String scope, String hash, IntConsumer authorize) {

    }

    /**
     * 幂等请求键命中的运行状态及原请求摘要。
     */
    record RequestMatch(Run run, String hash) {

    }
}
