package me.lendev.flow.workbench.todo;

/**
 * 所属单据或归集对象的稳定业务引用，保留对象 ID 的大小写。
 *
 * @param module     业务模块编码
 * @param objectType 模块内登记的对象类型编码
 * @param objectId   由所属模块规范化的字符串主键
 */
public record BusinessTodoRef(String module, String objectType, String objectId) {
}
