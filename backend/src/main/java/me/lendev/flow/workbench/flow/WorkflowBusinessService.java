package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.exception.BadRequestException.localized;

import jakarta.validation.Validator;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.TreeMap;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.field.FieldModels.Option;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 同应用业务服务的新版流程入口。外部系统由所属业务模块的网关鉴权后调用。
 */
@Service
@RequiredArgsConstructor
public class WorkflowBusinessService {

    private final WorkflowLifecycleService lifecycle;
    private final WorkflowDefinitionRepository definitions;
    private final WorkflowInstanceQueries queries;
    private final WorkflowBusinessRegistry modules;
    private final Validator validator;

    /**
     * 可订阅的流程事件类型：完成、取消、驳回、收回、待分派。
     */
    public enum EventType {COMPLETED, CANCELLED, RETURNED, RECALLED, WAITING_ASSIGNMENT}

    /**
     * 业务发起请求。requestKey 幂等，revision 缺省取最新已发布版本，
     * fields 仅允许模块声明的输入字段。
     */
    public record Start(@NotBlank @Pattern(regexp = "[!-~]{1,128}") String requestKey,
                        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]*") String templateCode,
                        @Positive Integer revision, @NotBlank @Size(max = 200) String title,
                        @Size(max = 100) Map<@NotBlank @Size(max = 64) String, JsonNode> fields,
                        @Size(max = 5) Map<@NotNull @Min(1) @Max(5) Integer, @NotNull @Positive Long> dimensions) {

    }

    /**
     * 业务取消请求，须携带实例当前版本与原因。
     */
    public record Cancel(@NotBlank @Pattern(regexp = "[!-~]{1,128}") String requestKey,
                         @NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 500) String reason) {

    }

    /**
     * 业务侧流程摘要，含模板绑定与当前状态。
     */
    public record Summary(long flowInstanceId, long definitionId, String templateCode, int revision, String title,
                          RunStatus status, long version, LocalDateTime startedAt, LocalDateTime closedAt) {

    }

    /**
     * 投递给业务模块的事件载荷。eventId 为去重标识，
     * fields 按模块 outputFields 声明裁剪。
     */
    public record Event(String eventId, int payloadVersion, EventType eventType, Source source,
                        String flowInstanceId, String definitionId, String templateCode, int revision,
                        long instanceVersion, RunStatus status, LocalDateTime occurredAt, Integer actorUserId,
                        String fromNodeKey, String toNodeKey, Map<String, JsonNode> fields) {

    }

    /**
     * 发起已登记业务单的流程，可加入调用方写事务。
     * 同键重试返回原实例及其当前状态，保留首次绑定的模板版本。
     *
     * @param source 业务来源三元组及可选发起人
     * @param input  模板、请求键、标题与初始字段
     * @return 实例摘要
     * @throws me.lendev.flow.common.exception.BadRequestException 来源未登记、无权发起或字段不合法
     * @throws TodoConflictException                               请求键重复但内容不一致
     */
    public Summary startFromBusiness(Source source, Start input) {
        validate(input);
        var reference = normalize(source);
        var module = modules.require(reference.module());
        var request = new Start(input.requestKey(), input.templateCode(), input.revision(), input.title().strip(),
                sorted(input.fields()), sorted(input.dimensions()));
        try (var ignored = AuditSystemContext.open("业务模块 " + reference.module())) {
            return AuditParticipants.active() ? lifecycle.startInAggregate(module, reference, request)
                    : lifecycle.startFromBusiness(module, reference, request);
        }
    }

    /**
     * 读取最新已发布模板的字段选项。业务表单可据此限制流程能接收的枚举值。
     * 返回空表示模板尚未发布，非枚举字段的选项为空列表。
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, List<String>>> publishedOptions(String templateCode) {
        return definitions.published(templateCode, null, false)
                .map(view -> view.fields().stream().collect(Collectors.toUnmodifiableMap(
                        WorkflowDefinitionModels.FieldBinding::code,
                        field -> field.options().stream().map(Option::code).toList())));
    }

    /**
     * 使用模块的业务读权限按三元组分页查询关联实例。
     *
     * @throws TodoNotFoundException 当前用户无权读取该业务单
     */
    public Page<Summary> findBySource(Source source, int viewer, int pageNo, int pageSize) {
        var reference = normalize(source);
        if (viewer < 1 || pageNo < 1 || pageSize < 1 || pageSize > 100) {
            throw localized("error.todo.request");
        }
        if (!modules.require(reference.module()).mayRead(reference, viewer)) {
            throw new TodoNotFoundException();
        }
        return queries.businessInstances(reference, pageNo, pageSize);
    }

    /**
     * 业务单授权后取消匹配的流程，和当前待办及结果事件一并提交。
     * 请求键幂等，重复取消重放当前状态。
     *
     * @throws TodoNotFoundException 实例不存在、来源不匹配或无权取消
     * @throws TodoConflictException 版本不匹配或流程已结束
     */
    public Summary cancelFromBusiness(Source source, long flowInstanceId, int actor, Cancel input) {
        validate(input);
        var reference = normalize(source);
        if (actor < 1 || flowInstanceId < 1 || !modules.require(reference.module()).mayCancel(reference, actor)) {
            throw new TodoNotFoundException();
        }
        try (var ignored = AuditSystemContext.open("业务模块 " + reference.module())) {
            var request = new Cancel(input.requestKey(), input.version(), input.reason().strip());
            return AuditParticipants.active() ? lifecycle.cancelInAggregate(reference, flowInstanceId, actor, request)
                    : lifecycle.cancelFromBusiness(reference, flowInstanceId, actor, request);
        }
    }

    private static Source normalize(Source source) {
        if (source == null || source.module() == null || !source.module().matches("[a-z][a-z0-9._-]{0,63}")
                || source.module().equals("workflow") || invalid(source.type(), 64) || invalid(source.id(), 128)
                || source.ownerId() != null && source.ownerId() < 1) {
            throw localized("error.todo.source");
        }
        return new Source(source.module(), source.type().strip(), source.id().strip(), source.ownerId());
    }

    private static boolean invalid(String value, int length) {
        return value == null || value.isBlank() || value.strip().length() > length || value.codePoints().anyMatch(Character::isISOControl);
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw localized("error.todo.request");
        }
    }

    private static <K extends Comparable<K>, V> Map<K, V> sorted(Map<K, V> entries) {
        return entries == null ? Map.of() : new TreeMap<>(entries);
    }

    /**
     * 判断业务来源三元组（模块、类型、ID）是否一致，忽略发起人。
     */
    static boolean sameSource(Source left, Source right) {
        return right != null && left.module().equals(right.module()) && left.type().equals(right.type())
                && Objects.equals(left.id(), right.id());
    }
}
