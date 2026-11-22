package me.lendev.flow.workbench.todo;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在调用方事务内登记或重放待办业务关联，保留待办原始来源及排期。
 */
@Service
public class TodoBusinessAssociationService {

    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private record RecordType(String module, String type) {
    }

    /**
     * 待办首次登记的业务上下文和完整归集关系。
     *
     * @param todoId         待办 ID
     * @param businessRecord 所属单据引用
     * @param taskKindCode   所属单据中的任务环节编码
     * @param references     规范化、去重后的附加归集对象，可为空集合
     * @param createdAt      UTC 毫秒精度的首次关联时间
     */
    public record Association(long todoId, BusinessTodoRef businessRecord, String taskKindCode,
                              Set<BusinessTodoRef> references, LocalDateTime createdAt) {

        public Association {
            references = Set.copyOf(references);
        }
    }

    private final TodoRepository todos;
    private final TodoBusinessAssociationRepository associations;
    private final TodoSchedule schedule;
    private final Map<RecordType, TodoBusinessAssociationPolicy> policies;

    TodoBusinessAssociationService(TodoRepository todos, TodoBusinessAssociationRepository associations,
                                   TodoSchedule schedule, List<TodoBusinessAssociationPolicy> policies) {
        this.todos = todos;
        this.associations = associations;
        this.schedule = schedule;
        this.policies = policies.stream().collect(Collectors.toUnmodifiableMap(
                policy -> new RecordType(policy.recordModule(), policy.recordType()), Function.identity()));
    }

    /**
     * 在外层事务中登记关联。相同内容并发或重复提交返回首次记录，归属由所属单据的登记策略验证。
     *
     * @param todoId         要关联的已有待办 ID，可在外层事务内创建
     * @param businessRecord 决定查看权限的所属单据
     * @param taskKindCode   模块登记的任务环节编码
     * @param references     全部归集对象，可传空集合；重复项及所属单据会被移除
     * @return 首次写入的关联数据，含数据库保存的时间
     * @throws BadRequestException   格式错误或登记策略不能证明来源和归属
     * @throws TodoNotFoundException 父待办不存在
     * @throws TodoConflictException 已有的关联与本次内容冲突
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Association associate(long todoId, BusinessTodoRef businessRecord, String taskKindCode,
                                 Collection<BusinessTodoRef> references) {
        var owner = normalize(businessRecord);
        if (!matches(CODE, taskKindCode) || references == null) {
            throw invalidRequest();
        }
        Set<BusinessTodoRef> unique = new HashSet<>();
        for (var reference : references) {
            unique.add(normalize(reference));
        }
        unique.remove(owner);
        var normalized = Set.copyOf(unique);

        var todo = todos.lock(todoId).orElseThrow(TodoNotFoundException::new);
        var policy = policies.get(new RecordType(owner.module(), owner.objectType()));
        if (policy == null || !policy.allows(todo, owner, taskKindCode, normalized)) {
            throw BadRequestException.localized("error.todo.business-association-source");
        }
        var association = new Association(todoId, owner, taskKindCode, normalized, schedule.now());
        if (associations.insertIfAbsent(association)) {
            return association;
        }
        var previous = associations.findForReplay(todoId);
        if (previous.businessRecord().equals(owner) && previous.taskKindCode().equals(taskKindCode)
                && previous.references().equals(normalized)) {
            return previous;
        }
        throw new TodoConflictException("error.todo.business-association-conflict");
    }

    private static BusinessTodoRef normalize(BusinessTodoRef ref) {
        if (ref == null || !matches(MODULE, ref.module()) || !matches(CODE, ref.objectType())
                || ref.objectId() == null || ref.objectId().codePoints().anyMatch(Character::isISOControl)) {
            throw invalidRequest();
        }
        String id = ref.objectId().strip();
        if (id.isEmpty() || id.codePointCount(0, id.length()) > 128) {
            throw invalidRequest();
        }
        return new BusinessTodoRef(ref.module(), ref.objectType(), id);
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static BadRequestException invalidRequest() {
        return BadRequestException.localized("error.todo.request");
    }
}
