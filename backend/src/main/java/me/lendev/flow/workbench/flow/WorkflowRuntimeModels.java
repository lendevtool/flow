package me.lendev.flow.workbench.flow;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.View;
import org.springframework.format.annotation.DateTimeFormat;
import tools.jackson.databind.JsonNode;

/**
 * 新版运行接口及分派数据。
 */
final class WorkflowRuntimeModels {

    private WorkflowRuntimeModels() {
    }

    /**
     * 实例列表范围：我的待办、我发起、我审批过、已完成、全部。
     */
    enum Scope {MINE, STARTED, APPROVED, DONE, ALL}

    /**
     * 运行操作：提交、驳回、收回、取消、重试分派。
     */
    enum Operation {SUBMIT, REJECT, RECALL, CANCEL, RETRY_ASSIGNMENT}

    /**
     * 节点尝试状态：办理中、待分派、完成、被驳回、被收回、已取消。
     */
    enum AttemptStatus {ACTIVE, WAITING_ASSIGNMENT, DONE, RETURNED, WITHDRAWN, CANCELLED}

    /**
     * 分派原因随轮次保存，补派时继续区分新待办与退回待办。
     */
    enum AssignmentReason {NORMAL, RETURNED}

    /**
     * 任务领取身份：主办、受托、广播抢办、会签。
     */
    enum Identity {PRIMARY, DELEGATE, BROADCAST, COUNTERSIGN}

    /**
     * 实例列表筛选条件。
     */
    record InstanceFilters(@Size(max = 200) String title, @Size(max = 100) String ownerName,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startedFrom,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startedTo) {

    }

    /**
     * 实例分页结果，scopeCounts 为各范围总数。
     */
    record InstancePage(List<Instance> pageData, long total, Map<Scope, Long> scopeCounts) {

    }

    /**
     * 发起流程请求，requestKey 为幂等键。
     */
    record Start(@NotBlank @Pattern(regexp = "[!-~]{1,128}") String requestKey,
                 @Positive long definitionId, @NotBlank @Size(max = 200) String title) {

    }

    /**
     * 运行操作请求。requestKey 幂等，version 乐观锁，todoId 定位任务，
     * targetNodeKey 仅驳回使用，fields 为本次提交的字段值。
     */
    record Action(@NotBlank @Pattern(regexp = "[!-~]{1,128}") String requestKey,
                  @NotNull @PositiveOrZero Long version, @Positive Long todoId,
                  @Size(max = 64) String targetNodeKey, @Size(max = 500) String reason,
                  @Size(max = 500) String opinion, @Size(max = 100) Map<String, JsonNode> fields) {

    }

    /**
     * 实例摘要视图，currentNodes 为当前办理中的节点。
     */
    record Instance(long id, long definitionId, String title, Integer ownerUserId, String ownerName,
                    int currentStepNo, RunStatus status, long version, LocalDateTime startedAt,
                    LocalDateTime closedAt, List<CurrentNode> currentNodes) {

    }

    /**
     * 当前节点摘要。
     */
    record CurrentNode(String nodeKey, String title, String status, String roleName, List<String> assigneeNames) {

    }

    /**
     * 节点尝试视图，含已处理人与当前办理人。
     */
    record NodeView(long id, String nodeKey, int stageNo, int attemptNo, String status, String roleName,
                    List<String> processedNames, List<String> assigneeNames) {

    }

    /**
     * 任务视图，含可编辑/必填字段、能否提交及可驳回目标。
     */
    record TaskView(long id, long todoId, String nodeKey, Identity identity, List<String> editableFields,
                    List<String> requiredFields, boolean canSubmit, List<String> rejectTargets) {

    }

    /**
     * 实例详情聚合：定义快照、字段值、节点与任务、附件及操作权限。
     */
    record Detail(Instance instance, View definition, Map<String, JsonNode> values, List<NodeView> nodes,
                  List<TaskView> tasks, boolean canRecall, boolean canCancel, boolean canRetry,
                  Map<String, String> attachmentNames, String sourceUrl, Map<String, String> roleNames,
                  Map<String, List<String>> plannedAssignees) {

    }

    /**
     * 事件历史条目，含操作人、意见与附件快照。
     */
    record History(long id, String type, String nodeKey, String targetNodeKey, Integer actorUserId, String actorName,
                   String identity, String reason, String opinion, LocalDateTime createdAt,
                   Map<String, JsonNode> attachments, Map<String, String> attachmentNames) {

    }

    /**
     * 角色成员。
     */
    record Member(int id, String name, boolean enabled) {

    }

    /**
     * 角色办理人配置：主办与备办列表。
     */
    record Handlers(List<Member> members, Integer primaryUserId, List<Integer> backupUserIds) {

    }

    /**
     * 办理人配置摘要，requiresPrimaryHandler 表示模板是否要求主办。
     */
    record HandlerSummary(Integer primaryUserId, List<Integer> backupUserIds, boolean primaryEnabled,
                          boolean requiresPrimaryHandler) {
    }

    /**
     * 办理人配置写入请求，备办列表为空表示清空。
     */
    record HandlerWrite(@Positive Integer primaryUserId,
                        @NotNull @Size(max = 500) List<@NotNull @Positive Integer> backupUserIds) {

    }

    /**
     * 委托创建请求，区间为 [startsAt, endsAt)。
     */
    record DelegationWrite(@Positive int delegateUserId, @NotNull LocalDateTime startsAt,
                           @NotNull LocalDateTime endsAt) {

    }

    /**
     * 委托视图，status 为推导的生效状态。
     */
    record Delegation(long id, int delegateUserId, String delegateName, LocalDateTime startsAt, LocalDateTime endsAt,
                      LocalDateTime cancelledAt, String status) {

    }

    /**
     * 委托待办转交请求，按待办 ID 批量处理。
     */
    record Forward(@NotNull @Size(max = 100) List<@NotNull @Positive Long> todoIds) {

    }

    /**
     * 单个待办转交结果。
     */
    record ForwardResult(long todoId, String status) {

    }

    /**
     * 委托详情：待处理与已转交的委托待办。
     */
    record DelegationDetail(Delegation delegation, List<DelegatedTodo> pending, List<DelegatedTodo> forwarded) {

    }

    /**
     * 委托待办摘要。
     */
    record DelegatedTodo(long todoId, long flowId, String title) {

    }

    /**
     * 附件元数据，documentId/versionId 指向文档服务。
     */
    record Attachment(String documentId, String versionId, String name, long sizeBytes) {

    }

    /**
     * 分派接收人。delegationId 仅受托身份非空。
     */
    record Receiver(int userId, Identity identity, Long delegationId) {

    }

    /**
     * 节点分派结果，missingRoles 为无法分派的角色编码。
     */
    record Dispatch(Integer roleId, String roleName, List<Receiver> receivers, List<String> missingRoles) {

        Dispatch(Integer roleId, String roleName, List<Receiver> receivers) {
            this(roleId, roleName, receivers, List.of());
        }
    }
}
