package me.lendev.flow.document;

import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;
import static me.lendev.flow.pms.department.persistence.table.DepartmentEntryTableDef.DEPARTMENT_ENTRY;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.domain.ParamItem;
import me.lendev.flow.param.mapper.ParamGroupMapper;
import me.lendev.flow.param.mapper.ParamItemMapper;
import me.lendev.flow.param.support.TodoDimensionParameters;
import me.lendev.flow.pms.mapper.DepartmentMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读取文档选项，并协调文档属性写入与参数定义变更。
 */
@Component
@RequiredArgsConstructor
public class DocumentDimensions {

    public static final String SLOT_GROUP = "DOC_DIMENSION";
    public static final String PRODUCT_GROUP = "DOC_PRODUCT";
    private static final String TEXT = "TEXT";
    private static final String SELECT = "SELECT";
    private static final List<String> SLOT_CODES = List.of("DIMENSION_1", "DIMENSION_2", "DIMENSION_3",
            "DIMENSION_4", "DIMENSION_5", "DIMENSION_6");

    private final DepartmentMapper departments;
    private final ParamGroupMapper groups;
    private final ParamItemMapper items;
    private final TodoDimensionParameters todoDimensions;

    /**
     * 供编辑和筛选展示的选项，停用的选项仍可用于识别历史值。
     */
    public record Option(long id, String name, boolean enabled) {

    }

    /**
     * 固定槽位的定义及其字典选项。
     */
    public record Dimension(int slot, String name, String inputType, boolean enabled, List<Option> options) {

    }

    /**
     * 文档元数据的可用选项和历史选项。
     */
    public record Options(List<Option> departments, List<Option> products, List<Dimension> dimensions) {

    }

    /**
     * 返回按部门、产品和固定槽位顺序排列的选项，含停用项。
     */
    @Transactional(readOnly = true)
    public Options options() {
        List<Option> departmentOptions = departments.list().stream()
                .map(row -> new Option(row.getId(), row.getName(), Boolean.TRUE.equals(row.getEnabled()))).toList();
        Map<String, List<Option>> dictionaries = new HashMap<>();
        List<Option> products = dictionaries.computeIfAbsent(PRODUCT_GROUP, this::parameterOptions);
        List<Dimension> dimensions = slots(group(SLOT_GROUP, false), false).stream().map(slot -> {
            String code = dictionaryCode(slot.getValue1(), slot.getValue2());
            List<Option> options = code == null ? List.of() : dictionaries.computeIfAbsent(code, this::parameterOptions);
            return new Dimension(slotNumber(slot), slot.getName(), slot.getValue1(), Boolean.TRUE.equals(slot.getEnable()), options);
        }).toList();
        return new Options(departmentOptions, products, dimensions);
    }

    /**
     * 在写事务中先锁定文档维度分类，再锁定文档和相关参数记录。
     */
    public void lockConfiguration() {
        group(SLOT_GROUP, true);
    }

    /**
     * 校验完整的文档属性。调用方持有写事务，并在读取待更新的文档前锁定配置。 保留未改动的停用选项，新增值仅允许使用当前启用的配置和选项。
     *
     * @return 仅包含非空且已规范化的维度值
     */
    public Map<Integer, String> validate(Long departmentId, Long productId, Map<Integer, String> dimensions,
                                         Long previousDepartmentId, Long previousProductId,
                                         Map<Integer, String> previousDimensions) {
        ParamGroup configuration = group(SLOT_GROUP, true);
        validateDepartment(departmentId, previousDepartmentId);
        if (productId != null && !Objects.equals(productId, previousProductId)) {
            requireEnabledOption(PRODUCT_GROUP, productId, "error.document.product");
        }
        if (dimensions == null || dimensions.isEmpty()) {
            return Map.of();
        }

        Map<Integer, ParamItem> definitions = new HashMap<>();
        for (ParamItem slot : slots(configuration, true)) {
            definitions.put(slotNumber(slot), slot);
        }
        if (definitions.entrySet().stream().anyMatch(entry -> dimensions.get(entry.getKey()) != null
                && bindsTodoParameters(entry.getValue()))) {
            // 待办先锁定自己的槽位分类，再访问它的字典。文档引用同一字典时沿用该顺序。
            todoDimensions.slotGroup(true);
        }
        Map<Integer, String> result = new HashMap<>();
        for (var entry : dimensions.entrySet()) {
            ParamItem definition = definitions.get(entry.getKey());
            if (definition == null) {
                throw BadRequestException.localized("error.document.dimension.slot");
            }
            String previous = previousDimensions == null ? null : previousDimensions.get(entry.getKey());
            String normalized = normalizeValue(definition, entry.getValue(), previous);
            if (normalized != null) {
                result.put(entry.getKey(), normalized);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 文档维度和产品使用稳定的内置分类编码。
     */
    public static void requireMutableGroup(String code) {
        if (SLOT_GROUP.equals(code) || PRODUCT_GROUP.equals(code)) {
            throw BadRequestException.localized("error.document.dimension.fixed");
        }
    }

    /**
     * 参数管理可编辑预置槽位，槽位数量和编码固定。
     */
    public static void requireMutableItems(String groupCode) {
        if (SLOT_GROUP.equals(groupCode)) {
            throw BadRequestException.localized("error.document.dimension.fixed");
        }
    }

    /**
     * 校验槽位定义，已有文档值的槽位保持输入类型及字典绑定。
     */
    public void updateSlot(ParamItem slot, String previousType, String previousDictionary) {
        int slotNumber = slotNumber(slot);
        if (slotNumber < 1) {
            throw BadRequestException.localized("error.document.dimension.slot");
        }
        String type = slot.getValue1();
        if (!TEXT.equals(type) && !SELECT.equals(type)) {
            throw BadRequestException.localized("error.document.dimension.type");
        }
        String dictionary = dictionaryCode(type, slot.getValue2());
        if (SELECT.equals(type) && (dictionary == null || group(dictionary, true) == null)) {
            throw BadRequestException.localized("error.document.dimension.dictionary");
        }
        boolean changed = !Objects.equals(previousType, type)
                || !Objects.equals(dictionaryCode(previousType, previousDictionary), dictionary);
        if (changed && items.hasDocumentDimensionValues(slotNumber)) {
            throw BadRequestException.localized("error.document.dimension.binding-in-use");
        }
        slot.setValue2(dictionary);
    }

    /**
     * 参数分类删除前检查是否仍绑定到文档维度。调用方须持有配置锁。
     */
    public void requireUnbound(String code) {
        if (slots(group(SLOT_GROUP, true), true).stream().anyMatch(slot -> binds(slot, code))) {
            throw BadRequestException.localized("error.document.dimension.dictionary-in-use");
        }
    }

    /**
     * 参数项删除前检查文档引用。停用选项仍可保留历史，调用方须持有配置锁。
     */
    public void requireUnreferenced(String code, long itemId) {
        boolean referencedProduct = PRODUCT_GROUP.equals(code) && items.hasDocumentProductReferences(itemId);
        if (referencedProduct || slots(group(SLOT_GROUP, true), true).stream().anyMatch(slot -> binds(slot, code)
                && items.hasDocumentOptionReferences(slotNumber(slot), itemId))) {
            throw BadRequestException.localized("error.document.dimension.option-in-use");
        }
    }

    private void validateDepartment(Long departmentId, Long previousDepartmentId) {
        if (departmentId == null || Objects.equals(departmentId, previousDepartmentId)) {
            return;
        }
        var department = departments.selectOneByQuery(QueryWrapper.create()
                .where(DEPARTMENT_ENTRY.ID.eq(departmentId)).forUpdate());
        if (department == null || !Boolean.TRUE.equals(department.getEnabled())) {
            throw BadRequestException.localized("error.document.department");
        }
    }

    private String normalizeValue(ParamItem definition, String raw, String previous) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > 255) {
            throw BadRequestException.localized("error.document.dimension.length");
        }
        boolean selection = SELECT.equals(definition.getValue1());
        long selectionId = 0;
        if (selection) {
            selectionId = optionId(value);
            value = Long.toString(selectionId);
        }
        if (Objects.equals(previous, value)) {
            return value;
        }
        if (!Boolean.TRUE.equals(definition.getEnable())) {
            throw BadRequestException.localized("error.document.dimension.disabled");
        }
        if (selection) {
            requireEnabledOption(definition.getValue2(), selectionId, "error.document.dimension.option");
        }
        return value;
    }

    private static long optionId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 字典值使用有效的参数项主键。
        }
        throw BadRequestException.localized("error.document.dimension.option");
    }

    private void requireEnabledOption(String code, long id, String error) {
        ParamGroup group = code == null ? null : group(code, true);
        if (group == null || !Boolean.TRUE.equals(group.getEnable())) {
            throw BadRequestException.localized(error);
        }
        ParamItem item = items.selectOneByQuery(QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(group.getId())
                .and(PARAM_ITEM.ID.eq(id))).forUpdate());
        if (item == null || !Boolean.TRUE.equals(item.getEnable())) {
            throw BadRequestException.localized(error);
        }
    }

    private List<Option> parameterOptions(String code) {
        ParamGroup group = group(code, false);
        if (group == null) {
            return List.of();
        }
        boolean groupEnabled = Boolean.TRUE.equals(group.getEnable());
        return items.selectListByQuery(QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(group.getId()))
                        .orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc())).stream()
                .map(item -> new Option(item.getId(), item.getName(), groupEnabled && Boolean.TRUE.equals(item.getEnable())))
                .toList();
    }

    private ParamGroup group(String code, boolean lock) {
        var query = QueryWrapper.create().where(PARAM_GROUP.CODE.eq(code));
        return groups.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    private List<ParamItem> slots(ParamGroup group, boolean lock) {
        if (group == null) {
            return List.of();
        }
        var query = QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(group.getId())
                .and(PARAM_ITEM.CODE.in(SLOT_CODES))).orderBy(PARAM_ITEM.CODE.asc());
        return items.selectListByQuery(lock ? query.forUpdate() : query);
    }

    private static int slotNumber(ParamItem slot) {
        return SLOT_CODES.indexOf(slot.getCode()) + 1;
    }

    private static boolean binds(ParamItem slot, String code) {
        return code.equals(dictionaryCode(slot.getValue1(), slot.getValue2()));
    }

    private static boolean bindsTodoParameters(ParamItem slot) {
        String code = dictionaryCode(slot.getValue1(), slot.getValue2());
        return code != null && TodoDimensionParameters.isProtected(code);
    }

    private static String dictionaryCode(String type, String raw) {
        return SELECT.equals(type) && raw != null && !raw.isBlank() ? raw.strip() : null;
    }
}
