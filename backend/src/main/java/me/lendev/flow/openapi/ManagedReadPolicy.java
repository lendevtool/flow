package me.lendev.flow.openapi;

import java.util.Set;

/**
 * 保存早期按固定路由登记的只读模块策略。
 */
public interface ManagedReadPolicy extends ManagedReadModule {
    /**
     * 列出此历史策略显式允许的来源路由。
     *
     * @return 允许注册的来源路径模板集合
     */
    Set<String> sourcePaths();
}
