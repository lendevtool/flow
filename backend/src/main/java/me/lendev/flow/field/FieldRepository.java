package me.lendev.flow.field;

import static me.lendev.flow.field.FieldModels.*;
import static me.lendev.flow.field.persistence.table.FieldDefinitionTableDef.FIELD_DEFINITION;

import java.util.List;
import java.util.Optional;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.field.mapper.FieldMapper;
import me.lendev.flow.field.persistence.FieldDefinition;
import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.domain.ParamItem;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 持久化字段配置并读取参数分类及其选项。
 */
@Repository
@RequiredArgsConstructor
public class FieldRepository {

    private final FieldMapper mapper;
    private final ObjectMapper json;

    public Page<Definition> page(Query query) {
        String search = query.keyword();
        String keyword = search == null || search.isBlank() ? null : keyword(search);
        var result = new Page<Definition>();
        var rows = mapper.page(keyword, query.dataType(), query.enable(),
                query.pageNo() == null ? 1 : query.pageNo(), query.size());
        result.setTotal(rows.getTotalRow());
        result.setPageData(rows.getRecords().stream().map(this::definition).toList());
        return result;
    }

    public Optional<Definition> find(long id, boolean lock) {
        return Optional.ofNullable(mapper.find(id, lock)).map(this::definition);
    }

    List<Definition> findByCodes(List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return mapper.selectListByQuery(QueryWrapper.create().where(FIELD_DEFINITION.CODE.in(codes)))
                .stream().map(this::definition).toList();
    }

    public long insert(Config config, long actor) {
        var row = new FieldDefinition();
        row.setCode(config.code());
        row.setCreatedBy(actor);
        applyConfig(row, config, actor);
        mapper.insertSelective(row);
        return row.getId();
    }

    public void update(long id, Config config, long actor) {
        var row = UpdateEntity.of(FieldDefinition.class, id);
        applyConfig(row, config, actor);
        mapper.update(row);
    }

    public void delete(long id) {
        mapper.deleteById(id);
    }

    /**
     * 与参数管理采用相同的分类行锁，保存期间字典的启用状态和明细保持稳定。
     */
    public Optional<Dictionary> dictionary(long id, boolean lock) {
        return Optional.ofNullable(mapper.dictionary(id, lock)).map(FieldRepository::dictionary);
    }

    public List<Dictionary> dictionaries(String search, Long selectedId) {
        return mapper.dictionaries(keyword(search == null ? "" : search), selectedId).stream()
                .map(FieldRepository::dictionary).toList();
    }

    /**
     * 锁定读取用于写入校验，确保 MySQL 下不会读取较早的一致性快照。
     */
    public List<Option> options(long groupId, boolean lock) {
        return mapper.options(groupId, lock).stream().map(FieldRepository::option).toList();
    }

    public List<Option> metadata(String code) {
        return mapper.metadata(code).stream().map(FieldRepository::option).toList();
    }

    private static String keyword(String value) {
        return "%" + value.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private void applyConfig(FieldDefinition row, Config config, long actor) {
        var value = config.defaultValue();
        row.setDefaultValue(value == null || value.isNull() ? null : json.writeValueAsString(value));
        row.setName(config.name());
        row.setNameEn(config.nameEn());
        row.setDataType(config.dataType());
        row.setRequired(config.required());
        row.setTextMinLength(config.textMinLength());
        row.setTextMaxLength(config.textMaxLength());
        row.setTextFormat(config.textFormat());
        row.setTextPattern(config.textPattern());
        row.setTextCharLimitEnabled(config.textCharLimitEnabled());
        row.setTextAllowChinese(config.textAllowChinese());
        row.setTextAllowEnglish(config.textAllowEnglish());
        row.setTextAllowDigits(config.textAllowDigits());
        row.setTextAllowSpace(config.textAllowSpace());
        row.setTextAllowSeparators(config.textAllowSeparators());
        row.setTextAllowChinesePunctuation(config.textAllowChinesePunctuation());
        row.setTextAllowEnglishPunctuation(config.textAllowEnglishPunctuation());
        row.setNumberPositiveOnly(config.numberPositiveOnly());
        row.setIntegerMin(config.integerMin());
        row.setIntegerMax(config.integerMax());
        row.setDecimalMin(config.decimalMin());
        row.setDecimalMax(config.decimalMax());
        row.setDecimalPrecision(config.decimalPrecision());
        row.setDecimalScale(config.decimalScale());
        row.setDateMin(config.dateMin());
        row.setDateMax(config.dateMax());
        row.setDateTimeMin(config.dateTimeMin());
        row.setDateTimeMax(config.dateTimeMax());
        row.setParamGroupId(config.paramGroupId());
        row.setEnumMinItems(config.enumMinItems());
        row.setEnumMaxItems(config.enumMaxItems());
        row.setEnable(config.enable());
        row.setRemark(config.remark());
        row.setUpdatedBy(actor);
    }

    private static Dictionary dictionary(ParamGroup group) {
        return new Dictionary(group.getId(), group.getCode(), group.getName(), group.getNameEn(), group.getEnable());
    }

    private static Option option(ParamItem item) {
        return new Option(item.getCode(), item.getName(), item.getNameEn(), item.getEnable());
    }

    private Definition definition(FieldDefinition row) {
        var defaultValue = row.getDefaultValue() == null ? null : json.readTree(row.getDefaultValue());
        var config = new Config(row.getCode(), row.getName(), row.getNameEn(), row.getDataType(), row.getRequired(), defaultValue,
                row.getTextMinLength(), row.getTextMaxLength(), row.getTextFormat(), row.getTextPattern(),
                row.getTextCharLimitEnabled(), row.getTextAllowChinese(), row.getTextAllowEnglish(), row.getTextAllowDigits(),
                row.getTextAllowSpace(), row.getTextAllowSeparators(), row.getTextAllowChinesePunctuation(),
                row.getTextAllowEnglishPunctuation(), row.getNumberPositiveOnly(), row.getIntegerMin(), row.getIntegerMax(),
                row.getDecimalMin(), row.getDecimalMax(), row.getDecimalPrecision(), row.getDecimalScale(), row.getDateMin(),
                row.getDateMax(), row.getDateTimeMin(), row.getDateTimeMax(), row.getParamGroupId(), row.getEnumMinItems(),
                row.getEnumMaxItems(), row.getEnable(), row.getRemark());
        return new Definition(row.getId(), config, row.getCreatedBy(), row.getCreatedAt(), row.getUpdatedBy(), row.getUpdatedAt());
    }
}
