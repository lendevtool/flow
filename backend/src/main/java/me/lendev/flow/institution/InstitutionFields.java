package me.lendev.flow.institution;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.institution.InstitutionRepository.COUNTRY_GROUP;
import static me.lendev.flow.institution.InstitutionRepository.TYPE_GROUP;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.field.FieldModels.Config;
import me.lendev.flow.field.FieldModels.Definition;
import me.lendev.flow.field.FieldService;
import me.lendev.flow.field.FieldValidator;
import me.lendev.flow.institution.InstitutionModels.ApprovalOptions;
import me.lendev.flow.institution.InstitutionModels.FieldOption;
import me.lendev.flow.institution.InstitutionModels.Option;
import me.lendev.flow.institution.InstitutionModels.Options;
import me.lendev.flow.institution.InstitutionModels.Save;
import me.lendev.flow.institution.InstitutionRepository.Dictionary;
import me.lendev.flow.institution.entity.InstitutionRow;
import me.lendev.flow.param.domain.ParamItem;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 按字段管理的编码校验业务值，并在机构字典 ID 和流程编码之间转换。
 */
@Component
@RequiredArgsConstructor
class InstitutionFields {

    static final String CODE = "institution_code";
    static final String NAME_ZH = "institution_name_zh";
    static final String NAME_EN = "institution_name_en";
    static final String TYPE = "institution_type";
    static final String COUNTRY = "institution_country_region";
    static final String REGISTRATION = "institution_registration_id";
    static final String SHORT_ZH = "institution_short_name_zh";
    static final String SHORT_EN = "institution_short_name_en";
    static final String ADDRESS = "institution_office_address";
    static final String WEBSITE = "institution_website";
    static final String REMARK = "institution_remark";
    static final String ENABLED = "institution_enabled";
    static final String REASON = "institution_change_reason";

    static final Set<String> APPROVABLE = Set.of(NAME_ZH, NAME_EN, TYPE, COUNTRY, REGISTRATION);
    static final Set<String> FLOW_FIELDS = Set.of(CODE, REASON, NAME_ZH, NAME_EN, TYPE, COUNTRY, REGISTRATION);
    private static final List<String> ALL = List.of(CODE, NAME_ZH, NAME_EN, TYPE, COUNTRY, REGISTRATION,
            SHORT_ZH, SHORT_EN, ADDRESS, WEBSITE, REMARK, ENABLED, REASON);
    private static final Set<String> MANDATORY = Set.of(CODE, NAME_ZH, TYPE, COUNTRY, REASON);

    private final InstitutionRepository repository;
    private final FieldService fieldService;
    private final FieldValidator validator;

    Options options(Optional<Map<String, List<String>>> published) {
        var context = context(false);
        var approvals = published.map(values -> new ApprovalOptions(values.getOrDefault(TYPE, List.of()),
                values.getOrDefault(COUNTRY, List.of()), values.keySet().stream().sorted().toList())).orElse(null);
        var metadata = fieldService.findByCodes(ALL).stream().map(Definition::config)
                .filter(Config::enable).collect(Collectors.toUnmodifiableMap(Config::code, config ->
                        new FieldOption(config.name(), config.nameEn(), MANDATORY.contains(config.code()) || config.required(),
                                config.textMaxLength() == null ? null : Math.min(config.textMaxLength(), width(config.code())))));
        return new Options(options(context.types()), options(context.countries()), approvals, metadata);
    }

    private static List<Option> options(Dictionary dictionary) {
        return dictionary.items().stream().map(item -> new Option(item.getId(), item.getCode(), item.getName(),
                item.getNameEn(), dictionary.enabled() && Boolean.TRUE.equals(item.getEnable()))).toList();
    }

    Context context(boolean lock) {
        // 写路径按参数分类和明细的顺序锁定，避免选项在验证与写入间失效。
        return new Context(repository.dictionary(TYPE_GROUP, lock), repository.dictionary(COUNTRY_GROUP, lock));
    }

    record Context(Dictionary types, Dictionary countries) {

        Dictionary dictionary(String field) {
            return switch (field) {
                case TYPE -> types;
                case COUNTRY -> countries;
                default -> null;
            };
        }

        String code(String field, long id) {
            return dictionary(field).items().stream().filter(item -> item.getId() == id)
                    .map(ParamItem::getCode).findFirst().orElseThrow(() -> localized("error.institution.option"));
        }

        long id(String field, String code) {
            return dictionary(field).items().stream().filter(item -> item.getCode().equals(code))
                    .map(ParamItem::getId).findFirst().orElseThrow(() -> localized("error.institution.option"));
        }
    }

    Save normalize(Save value) {
        return new Save(clean(value.code()), clean(value.nameZh()), clean(value.nameEn()), value.typeItemId(),
                value.countryRegionItemId(), clean(value.registrationId()), clean(value.shortNameZh()),
                clean(value.shortNameEn()), clean(value.officeAddress()), clean(value.website()), clean(value.remark()),
                value.version());
    }

    void validateForm(Save value, InstitutionRow current) {
        var context = context(true);
        var data = new LinkedHashMap<String, JsonNode>();
        data.put(CODE, text(value.code()));
        data.put(NAME_ZH, text(value.nameZh()));
        data.put(NAME_EN, text(value.nameEn()));
        data.put(TYPE, text(context.code(TYPE, value.typeItemId())));
        data.put(COUNTRY, text(context.code(COUNTRY, value.countryRegionItemId())));
        data.put(REGISTRATION, text(value.registrationId()));
        data.put(SHORT_ZH, text(value.shortNameZh()));
        data.put(SHORT_EN, text(value.shortNameEn()));
        data.put(ADDRESS, text(value.officeAddress()));
        data.put(WEBSITE, text(value.website()));
        data.put(REMARK, text(value.remark()));
        var existing = current == null ? Map.<String, JsonNode>of() : snapshot(current, context);
        validate(data, context, existing);
    }

    void validate(Map<String, JsonNode> values, Context context, Map<String, JsonNode> original) {
        var definitions = fieldService.findByCodes(List.copyOf(values.keySet())).stream()
                .map(Definition::config).collect(Collectors.toMap(Config::code, Function.identity()));
        values.forEach((code, value) -> {
            var definition = definitions.get(code);
            var dictionary = context.dictionary(code);
            var expected = dictionary == null ? "TEXT" : "ENUM";
            if (definition == null || !definition.enable() || !expected.equals(definition.dataType().name())
                    || dictionary != null && !Objects.equals(dictionary.id(), definition.paramGroupId())) {
                throw localized("error.institution.field", code);
            }
            Set<String> options = dictionary == null ? Set.of() : dictionary.items().stream()
                    .filter(item -> dictionary.enabled() && Boolean.TRUE.equals(item.getEnable())
                            || text(item.getCode()).equals(original.get(code)))
                    .map(ParamItem::getCode).collect(Collectors.toSet());
            validator.value(definition, value, options, MANDATORY.contains(code) || definition.required());
            if (value != null && value.isString() && value.asString().length() > width(code)) {
                throw localized("error.institution.length", code, width(code));
            }
        });
    }

    private static int width(String field) {
        return switch (field) {
            case CODE, TYPE, COUNTRY -> 64;
            case NAME_ZH, NAME_EN -> 200;
            case REGISTRATION, SHORT_ZH, SHORT_EN -> 100;
            case REMARK -> 1000;
            default -> 500;
        };
    }

    static Map<String, JsonNode> snapshot(InstitutionRow row, Context context) {
        return Map.of(NAME_ZH, text(row.getNameZh()), NAME_EN, text(row.getNameEn()),
                TYPE, text(context.code(TYPE, row.getTypeItemId())), COUNTRY,
                text(context.code(COUNTRY, row.getCountryRegionItemId())), REGISTRATION, text(row.getRegistrationId()));
    }

    static JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) {
            return text(null);
        }
        if (!value.isString()) {
            throw localized("error.institution.values");
        }
        return text(clean(value.asString()));
    }

    static JsonNode text(String value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.stringNode(value);
    }

    static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String string(JsonNode value) {
        return value.isNull() ? null : value.asString();
    }

    static void apply(InstitutionRow row, Map<String, JsonNode> approved, Context context) {
        approved.forEach((code, value) -> {
            switch (code) {
                case NAME_ZH -> row.setNameZh(string(value));
                case NAME_EN -> row.setNameEn(string(value));
                case TYPE -> row.setTypeItemId(context.id(code, string(value)));
                case COUNTRY -> row.setCountryRegionItemId(context.id(code, string(value)));
                case REGISTRATION -> row.setRegistrationId(string(value));
                default -> throw new IllegalStateException("未声明的机构字段 " + code);
            }
        });
    }
}
