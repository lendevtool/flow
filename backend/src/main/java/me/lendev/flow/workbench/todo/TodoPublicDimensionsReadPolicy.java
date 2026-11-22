package me.lendev.flow.workbench.todo;

import java.util.Map;
import java.util.Set;

import me.lendev.flow.openapi.ManagedReadPolicy;
import org.springframework.stereotype.Component;

/**
 * 允许获授权的应用读取待办公共维度的槽位元数据。
 */
@Component
class TodoPublicDimensionsReadPolicy implements ManagedReadPolicy {
    @Override
    public Class<?> controllerType() {
        return TodoController.class;
    }

    @Override
    public String moduleCode() {
        return "workbench";
    }

    @Override
    public String dataScopeType() {
        return "NONE";
    }

    @Override
    public String scopePolicyVersion() {
        return "todo-public-dimensions-v1";
    }

    @Override
    public Map<Class<?>, Set<String>> responseFields() {
        return Map.of(TodoPublicDimensions.Dimension.class, Set.of("slot", "name", "enabled"));
    }

    @Override
    public Set<String> sourcePaths() {
        return Set.of(TodoController.ROOT_PATH + TodoController.PUBLIC_DIMENSIONS_PATH);
    }
}
