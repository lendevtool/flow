package me.lendev.flow.workbench.todo;

import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.domain.ParamItem;
import me.lendev.flow.param.domain.ParamRecord;
import me.lendev.flow.param.mapper.ParamGroupMapper;
import me.lendev.flow.param.mapper.ParamItemMapper;
import me.lendev.flow.param.support.TodoDimensionParameters;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询待办公共维度，并在业务写入的同一事务内校验选项及固定字典绑定。
 */
@Service
@RequiredArgsConstructor
public class TodoPublicDimensions {

    private static final String INVALID_OPTION = "error.todo.dimension.option";
    private final TodoDimensionParameters parameters;
    private final ParamGroupMapper groups;
    private final ParamItemMapper items;

    /**
     * 待办公共维度槽位，停用槽位及其选项仍可用于展示和筛选历史值。
     */
    public record Dimension(int slot, String name, boolean enabled, List<Option> options) {

    }

    /**
     * 选项使用稳定参数项主键，启用状态表示当前能否填写为新值。
     */
    public record Option(long id, String name, boolean enabled) {

    }

    /**
     * 按槽位号返回配置，名称使用当前请求语言。保留停用选项以支持历史查询。
     *
     * @return 配置中的固定槽位和各槽位的选项
     */
    @Transactional(readOnly = true)
    public List<Dimension> list() {
        ParamGroup slotGroup = parameters.slotGroup(false);
        if (slotGroup == null) {
            return List.of();
        }
        var slots = slotsByCode(slotGroup, false);
        List<Dimension> dimensions = new ArrayList<>(TodoDimensionParameters.SLOT_COUNT);
        for (int number = 1; number <= TodoDimensionParameters.SLOT_COUNT; number++) {
            ParamItem slot = slots.get(TodoDimensionParameters.slotCode(number));
            if (slot != null) {
                dimensions.add(dimension(number, slot));
            }
        }
        return List.copyOf(dimensions);
    }

    private Dimension dimension(int number, ParamItem slot) {
        Long dictionaryId = TodoDimensionParameters.dictionaryId(slot);
        ParamGroup dictionary = dictionaryId == null ? null : groups.selectOneById(dictionaryId);
        boolean enabled = Boolean.TRUE.equals(slot.getEnable()) && dictionary != null
                && Boolean.TRUE.equals(dictionary.getEnable());
        List<Option> options = dictionary == null ? List.of() : items.selectListByQuery(QueryWrapper.create()
                        .where(PARAM_ITEM.GROUP_ID.eq(dictionaryId)).orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc()))
                .stream().map(item -> new Option(item.getId(), localizedName(item),
                        enabled && Boolean.TRUE.equals(item.getEnable()))).toList();
        return new Dimension(number, localizedName(slot), enabled, options);
    }

    /**
     * 校验写入的公共维度值，按参数分类和明细顺序锁定配置，并在首次引用时固定绑定。 调用方必须持有写事务，待办、规则或流程保存失败时，绑定标记同时回滚。
     *
     * @param values    要写入的槽位号与选项 ID，空值表示清空槽位
     * @param existing  数据库中已保存的原值，可原样保留停用值
     * @param inherited 是否由受信任的周期规则或流程实例复制已保存的值
     * @throws BadRequestException 槽位无效、字典不匹配或新选择的值已停用时抛出
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validate(Map<Integer, Long> values, Map<Integer, Long> existing, boolean inherited) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (values.keySet().stream().anyMatch(slot -> slot == null || slot < 1
                || slot > TodoDimensionParameters.SLOT_COUNT)) {
            throw BadRequestException.localized("error.todo.dimension.slot");
        }
        if (values.values().stream().allMatch(Objects::isNull)) {
            return;
        }
        ParamGroup slotGroup = parameters.slotGroup(true);
        if (slotGroup == null) {
            throw BadRequestException.localized("error.todo.dimension.dictionary");
        }
        Map<String, ParamItem> slots = slotsByCode(slotGroup, true);
        for (int number = 1; number <= TodoDimensionParameters.SLOT_COUNT; number++) {
            Long itemId = values.get(number);
            if (itemId != null) {
                ParamItem slot = slots.get(TodoDimensionParameters.slotCode(number));
                boolean retained = existing != null && itemId.equals(existing.get(number));
                validateOption(slot, itemId, inherited || retained);
                parameters.freezeBinding(slotGroup, slot);
            }
        }
    }

    private void validateOption(ParamItem slot, Long itemId, boolean allowDisabled) {
        Long dictionaryId = slot == null ? null : TodoDimensionParameters.dictionaryId(slot);
        if (dictionaryId == null) {
            throw BadRequestException.localized(INVALID_OPTION);
        }
        ParamGroup dictionary = groups.selectOneByQuery(QueryWrapper.create()
                .where(PARAM_GROUP.ID.eq(dictionaryId)).forUpdate());
        if (dictionary == null || !TodoDimensionParameters.isDictionary(dictionary.getCode())) {
            throw BadRequestException.localized(INVALID_OPTION);
        }
        ParamItem item = items.selectOneByQuery(QueryWrapper.create()
                .where(PARAM_ITEM.ID.eq(itemId).and(PARAM_ITEM.GROUP_ID.eq(dictionaryId))).forUpdate());
        if (item == null || !allowDisabled && !(Boolean.TRUE.equals(slot.getEnable())
                && Boolean.TRUE.equals(dictionary.getEnable()) && Boolean.TRUE.equals(item.getEnable()))) {
            throw BadRequestException.localized(INVALID_OPTION);
        }
    }

    private Map<String, ParamItem> slotsByCode(ParamGroup group, boolean lock) {
        return parameters.slots(group.getId(), lock).stream()
                .collect(Collectors.toMap(ParamItem::getCode, Function.identity()));
    }

    private static String localizedName(ParamRecord row) {
        if (Locale.ENGLISH.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage())
                && row.getNameEn() != null && !row.getNameEn().isBlank()) {
            return row.getNameEn();
        }
        return row.getName();
    }
}
