package me.lendev.flow.workbench.todo;

import com.mybatisflex.core.query.QueryCondition;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务日历场景由可信 Spring Bean 登记。授权条件应用于待办、所属单据和可选归集对象。
 */
public interface BusinessTodoCalendarScene {

    /**
     * 返回启动时登记的固定场景及维度定义。
     *
     * @return 场景定义
     */
    Definition definition();

    /**
     * @return 业务容器默认视图，可选 week 或 month
     */
    default String defaultView() {
        return "month";
    }

    /**
     * 提供可用于 SQL 的单据和任务级授权条件。可引用待办表及业务上下文表，关联其他表用子查询。
     * 拒绝访问时返回始终为假的条件或空值，允许该场景全部记录时必须显式返回真条件。
     *
     * @param viewer 当前登录用户 ID
     * @return 目标用户可见待办的查询条件
     */
    QueryCondition visibleTodos(int viewer);

    /**
     * 校验固定入口对象，施加在所属单据可见性之外。
     *
     * @param context 由服务端场景类型和请求中的 ID 解析出的对象
     * @param viewer  当前登录用户 ID
     * @return 是否可以访问该入口
     */
    default boolean canReadContext(BusinessTodoRef context, int viewer) {
        return false;
    }

    /**
     * 返回业务摘要允许展示的标准环节名称。
     *
     * @param taskKindCode 登记的任务环节编码
     * @return 展示名称，缺省使用稳定环节编码
     */
    default String taskLabel(String taskKindCode) {
        return taskKindCode;
    }

    /**
     * 批量解析已授权业务对象的展示名称。未提供的对象用稳定 ID 显示。
     *
     * @param records 当前查询中有权读取的所属单据或入口对象
     * @param viewer  当前登录用户 ID
     * @return 按业务引用匹配的展示名称
     */
    default Map<BusinessTodoRef, String> recordLabels(Set<BusinessTodoRef> records, int viewer) {
        return Map.of();
    }

    /**
     * 为已授权摘要提供来源链接和办理链接，生成时按各目标权限分别核对。
     *
     * @param businessRecord 任务所属单据
     * @param todoId         待办 ID
     * @param viewer         当前登录用户 ID
     * @return 可用的站内路径，缺省不展示
     */
    default Links links(BusinessTodoRef businessRecord, long todoId, int viewer) {
        return new Links(null, null);
    }

    /**
     * 固定范围按所属单据或归集对象匹配。
     */
    enum ContextKind {RECORD, LINK}

    /**
     * @param kind       固定入口使用的查询关系
     * @param module     入口对象所属模块
     * @param objectType 入口对象类型
     */
    record Context(ContextKind kind, String module, String objectType) {
    }

    /**
     * @param code         唯一场景编码
     * @param title        页面标题
     * @param permission   场景查看权限
     * @param recordModule 可查询的所属单据模块
     * @param recordType   可查询的所属单据类型
     * @param context      固定业务对象定义，总览场景为空
     * @param dimensions   依展示顺序声明的筛选维度
     */
    record Definition(String code, String title, String permission, String recordModule, String recordType,
                      Context context, List<BusinessTodoCalendarDimension> dimensions) {
    }

    /**
     * @param businessUrl 用户有权限打开的业务站内路径，可空
     * @param workUrl     用户有权限办理的工作台或业务站内路径，可空
     */
    record Links(String businessUrl, String workUrl) {
    }
}
