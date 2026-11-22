package me.lendev.flow.openapi;

import java.util.Map;
import java.util.Set;

/**
 * 声明已审查的只读 Controller、模块共用的应用授权和可公开字段。
 * 通用注册仅接受显式 GET 方法组成的 Controller。
 */
public interface ManagedReadModule {
    /**
     * 返回已审查且允许导入的只读来源控制器。
     *
     * @return 注册于 MVC 的控制器类型
     */
    Class<?> controllerType();

    /**
     * 返回来源登记和开放 API 目录使用的业务模块编码。
     *
     * @return 模块的稳定编码
     */
    String moduleCode();

    /**
     * 提供来源契约保留的范围类型标记，用于验证历史契约指纹。
     *
     * @return 来源契约类型标记
     */
    String dataScopeType();

    /**
     * 声明来源公开范围规则的版本，参与来源契约匹配。
     *
     * @return 范围策略的稳定版本标识
     */
    String scopePolicyVersion();

    /**
     * 限制来源响应中的哪些业务字段可以发布。
     *
     * @return 从响应类型到允许公开字段名集合的映射
     */
    Map<Class<?>, Set<String>> responseFields();
}
