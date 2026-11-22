package me.lendev.flow.workbench.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

/**
 * 新版流程适配器按业务模块编码路由。
 */
@Component
@Slf4j
class WorkflowBusinessRegistry {

    private final Map<String, WorkflowBusinessSource> modules;
    private final Set<String> businessOnlyTemplates;

    /**
     * 启动时登记全部适配器，编码无效、保留或重复时直接终止启动。
     */
    WorkflowBusinessRegistry(List<WorkflowBusinessSource> sources) {
        var registry = new HashMap<String, WorkflowBusinessSource>();
        for (var source : sources) {
            String code = source.module();
            if (code == null || !code.matches("[a-z][a-z0-9._-]{0,63}") || code.equals("workflow")
                    || registry.putIfAbsent(code, source) != null) {
                throw new IllegalArgumentException("流程业务模块编码无效或重复：" + code);
            }
        }
        modules = Map.copyOf(registry);
        businessOnlyTemplates = sources.stream().flatMap(source -> source.businessOnlyTemplates().stream())
                .collect(Collectors.toUnmodifiableSet());
        log.info("流程业务适配器登记完成，模块数={}，模块={}", modules.size(), modules.keySet());
    }

    Set<String> businessOnlyTemplates() {
        return businessOnlyTemplates;
    }

    List<String> editableFields(WorkflowRun flow, WorkflowDefinitionModels.Node node) {
        var template = flow.editable(node);
        var source = flow.source();
        if (source == null || template.isEmpty()) {
            return template;
        }
        var allowed = require(source.module()).editableFields(source, template);
        return template.stream().filter(allowed::contains).toList();
    }

    /**
     * 按模块编码取得适配器。
     *
     * @throws BadRequestException 模块未登记
     */
    WorkflowBusinessSource require(String module) {
        var source = modules.get(module);
        if (source == null) {
            throw BadRequestException.localized("error.todo.source");
        }
        return source;
    }
}
