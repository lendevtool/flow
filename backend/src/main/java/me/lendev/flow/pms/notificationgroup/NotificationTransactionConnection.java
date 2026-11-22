package me.lendev.flow.pms.notificationgroup;

import java.sql.SQLException;
import javax.sql.DataSource;

import com.mybatisflex.core.transaction.TransactionContext;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 将通知群组事务声明的隔离级别设置到实际执行 Mapper 的连接。
 */
@Component
public class NotificationTransactionConnection {

    private final DataSource dataSource;

    NotificationTransactionConnection(SqlSessionFactory sessions) {
        dataSource = sessions.getConfiguration().getEnvironment().getDataSource();
    }

    /**
     * 当前 MyBatis-Flex 事务管理器保留 Spring 隔离声明，但未把它传递到连接。
     * 新事务在首条业务 SQL 前配置，连接仍由 Flex 提交并归还连接池。
     * 加入外部事务时要求连接已采用所需隔离级别，避免变更隔离触发已有事务的隐式提交。
     *
     * @param newTransaction 当前入口是否拥有事务
     */
    public void prepare(boolean newTransaction) {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation == null || TransactionContext.getXID() == null) {
            throw new IllegalStateException("通知群组需要指定隔离级别的 MyBatis-Flex 事务");
        }
        try (var connection = dataSource.getConnection()) {
            if (connection.getTransactionIsolation() == isolation) {
                return;
            }
            if (!newTransaction) {
                throw new IllegalStateException("通知群组不能修改外部事务的连接隔离级别");
            }
            connection.setTransactionIsolation(isolation);
        } catch (SQLException error) {
            throw new DataAccessResourceFailureException("无法设置通知群组事务隔离级别", error);
        }
    }
}
