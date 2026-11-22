package me.lendev.flow.institution;

import java.util.Map;
import java.util.Set;

import me.lendev.flow.institution.InstitutionModels.Institution;
import me.lendev.flow.openapi.ManagedReadModule;
import org.springframework.stereotype.Component;

/**
 * 声明机构档案只读来源和可公开字段，保留已发布映射的来源契约标记。
 */
@Component
class InstitutionReadModule implements ManagedReadModule {
    @Override
    public Class<?> controllerType() {
        return InstitutionReadController.class;
    }

    @Override
    public String moduleCode() {
        return "institution";
    }

    @Override
    public String dataScopeType() {
        return "INSTITUTION";
    }

    @Override
    public String scopePolicyVersion() {
        return "institution-v1";
    }

    @Override
    public Map<Class<?>, Set<String>> responseFields() {
        return Map.of(Institution.class, Set.of("id", "code", "nameZh", "nameEn", "shortNameZh", "shortNameEn",
                "typeItemId", "countryRegionItemId", "enabled"));
    }
}
