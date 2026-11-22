package me.lendev.flow.pms.notificationgroup;

import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;

import com.mybatisflex.core.dialect.DbType;
import com.mybatisflex.core.dialect.DbTypeUtil;
import me.lendev.flow.pms.notificationgroup.NotificationGroupModels.DepartmentSelection;
import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.RecipientScope;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 预览和运行时共用一致读快照与部门展开规则。
 */
@Component
class NotificationReadSnapshot {

    private final NotificationGroupStore store;
    private final NotificationTransactionConnection connection;
    private final TransactionTemplate snapshot;
    private final boolean h2;

    NotificationReadSnapshot(NotificationGroupStore store, PlatformTransactionManager manager,
                             DataSourceProperties properties, NotificationTransactionConnection connection) {
        this.store = store;
        this.connection = connection;
        this.h2 = DbTypeUtil.parseDbType(properties.getUrl()) == DbType.H2;
        this.snapshot = new TransactionTemplate(manager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setReadOnly(true);
        // H2 的 REPEATABLE_READ 可能在后续首次查询其他表时读到新提交的数据。
        snapshot.setIsolationLevel(h2 ? TransactionDefinition.ISOLATION_SERIALIZABLE
                : TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 启动独立只读事务，所有参与表共用本次读取视图。
     */
    <T> T read(Supplier<T> query) {
        return snapshot.execute(status -> {
            connection.prepare(status.isNewTransaction());
            return query.get();
        });
    }

    /**
     * 在当前快照内一次展开部门范围，构造可由数据库全局去重的候选范围。
     */
    RecipientScope recipients(Collection<Long> users, Collection<DepartmentSelection> departments) {
        Set<Long> expanded = departments.isEmpty() ? Set.of() : store.departments().expand(departments);
        return RecipientScope.of(h2, users, expanded);
    }
}
