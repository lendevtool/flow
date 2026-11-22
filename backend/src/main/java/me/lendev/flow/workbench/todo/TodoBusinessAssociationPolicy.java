package me.lendev.flow.workbench.todo;

import java.util.Set;

import me.lendev.flow.workbench.todo.TodoModels.Todo;

/**
 * 业务模块登记的关联写入策略。每个所属单据模块和类型只能登记一个策略。
 */
public interface TodoBusinessAssociationPolicy {

    /**
     * @return 策略负责的所属单据模块编码
     */
    String recordModule();

    /**
     * @return 策略负责的所属单据类型编码
     */
    String recordType();

    /**
     * 核对待办来源、单据存在性、环节和值对象的归属关系。父待办已在同一短事务内加锁，
     * 可检查同事务内已创建的业务数据；调用期间应避免外部调用和副作用。
     *
     * @param task           当前待办及其原始来源，流程节点来源为流程实例
     * @param businessRecord 规范化的所属单据引用
     * @param taskKindCode   所属单据的任务环节编码
     * @param references     去重后的全部附加归集对象
     * @return 可以证明待办与单据、环节和全部归集对象的关系时为 true
     */
    boolean allows(Todo task, BusinessTodoRef businessRecord, String taskKindCode, Set<BusinessTodoRef> references);
}
