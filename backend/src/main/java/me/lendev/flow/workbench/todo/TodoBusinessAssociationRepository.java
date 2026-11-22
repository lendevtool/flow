package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessContextTableDef.TODO_BUSINESS_CONTEXT;
import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessLinkTableDef.TODO_BUSINESS_LINK;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.Objects;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.workbench.todo.TodoBusinessAssociationService.Association;
import me.lendev.flow.workbench.todo.entity.TodoBusinessContext;
import me.lendev.flow.workbench.todo.entity.TodoBusinessLink;
import me.lendev.flow.workbench.todo.mapper.TodoBusinessContextMapper;
import me.lendev.flow.workbench.todo.mapper.TodoBusinessLinkMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 业务上下文和归集关系的事务内持久化。
 */
@Repository
@RequiredArgsConstructor
class TodoBusinessAssociationRepository {

    private final TodoBusinessContextMapper contexts;
    private final TodoBusinessLinkMapper links;

    /**
     * 父待办加锁后执行当前读，避免外层事务的旧快照漏读并发提交的关联。
     */
    Association findForReplay(long todoId) {
        var context = Objects.requireNonNull(contexts.selectOneByQuery(QueryWrapper.create()
                .where(TODO_BUSINESS_CONTEXT.TODO_ID.eq(todoId)).forUpdate()));
        var references = links.selectListByQuery(QueryWrapper.create()
                        .where(TODO_BUSINESS_LINK.TODO_ID.eq(todoId)).forUpdate()).stream()
                .map(row -> new BusinessTodoRef(row.getRefModule(), row.getRefType(), row.getRefId()))
                .collect(Collectors.toUnmodifiableSet());
        var businessRecord = new BusinessTodoRef(context.getRecordModule(), context.getRecordType(), context.getRecordId());
        return new Association(todoId, businessRecord, context.getTaskKindCode(), references, context.getCreatedAt());
    }

    /**
     * 插入新关联。优先尝试插入上下文，避免在 InnoDB 中锁住缺失主键的间隙，
     * 影响其他待办的插入。父待办已加锁，唯一的键冲突表示该待办已经登记。
     */
    boolean insertIfAbsent(Association association) {
        var context = new TodoBusinessContext();
        context.setTodoId(association.todoId());
        context.setRecordModule(association.businessRecord().module());
        context.setRecordType(association.businessRecord().objectType());
        context.setRecordId(association.businessRecord().objectId());
        context.setTaskKindCode(association.taskKindCode());
        context.setCreatedAt(association.createdAt());
        try {
            contexts.insert(context);
        } catch (DuplicateKeyException ignored) {
            return false;
        }
        if (!association.references().isEmpty()) {
            links.insertBatch(association.references().stream().map(ref -> link(association, ref)).toList());
        }
        return true;
    }

    private static TodoBusinessLink link(Association association, BusinessTodoRef ref) {
        var link = new TodoBusinessLink();
        link.setTodoId(association.todoId());
        link.setRefModule(ref.module());
        link.setRefType(ref.objectType());
        link.setRefId(ref.objectId());
        link.setCreatedAt(association.createdAt());
        return link;
    }
}
