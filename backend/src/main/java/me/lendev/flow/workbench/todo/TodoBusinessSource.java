package me.lendev.flow.workbench.todo;

import me.lendev.flow.workbench.todo.TodoModels.Source;

/**
 * 业务模块登记待办来源并约束可指派人员。实现由调用模块提供。
 */
public interface TodoBusinessSource {

    /**
     * 业务模块标识，全局唯一，用于幂等作用域和来源登记匹配。
     */
    String module();

    /**
     * 验证业务引用、可选发起人和负责人属于模块允许的范围。
     */
    boolean mayCreate(Source source, int assigneeUserId);

    /**
     * 待办列表展示的来源名称，默认与模块标识相同。
     */
    default String displayName() {
        return module();
    }

    /**
     * 按业务权限返回应用内相对路径；当前用户无权访问时返回空。
     */
    default String openPath(Source source, int viewer) {
        return null;
    }
}
