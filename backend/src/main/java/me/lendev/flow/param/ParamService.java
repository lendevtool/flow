package me.lendev.flow.param;

import static me.lendev.flow.common.validation.EnglishName.normalizeEnglishName;
import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.core.query.QueryWrapper;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.ResourceNotFoundException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.document.DocumentDimensions;
import me.lendev.flow.param.domain.*;
import me.lendev.flow.param.mapper.*;
import me.lendev.flow.param.request.*;
import me.lendev.flow.param.support.*;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.mapper.UserMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 管理参数分类、明细、排序和启用选项。影响选项缓存的更新在同一事务内递增参数组版本。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParamService {

    private static final String DEFAULT_VALUE = "defaultValue";
    private static final String TRADING_MARKET = "TRADING_MARKET";
    private static final int SORT_BATCH_SIZE = 250;
    private static final Pattern TRADING_MARKET_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern TRADING_MARKET_ICON = Pattern.compile("[a-z]{2}");

    private final ParamGroupMapper groups;
    private final ParamItemMapper items;
    private final UserMapper users;
    private final ParamFieldValidator validator;
    private final ParamLookupCache cache;
    private final TodoDimensionParameters todoDimensions;
    private final DocumentDimensions documentDimensions;
    private final ObjectMapper json;

    public Page<ParamGroup> groups(ParamPageRequest request) {
        var page = groups.paginate(request.toPage(), groupPageQuery(request));
        names(page.getRecords());
        return Page.convert(page);
    }

    Page<ParamCatalogModels.Group> catalogGroups(ParamPageRequest request) {
        var page = groups.paginate(request.toPage(), groupPageQuery(request));
        return catalogPage(page, ParamCatalogModels.Group::from);
    }

    private QueryWrapper groupPageQuery(ParamPageRequest request) {
        String keyword = keyword(request);
        return QueryWrapper.create()
                .where(PARAM_GROUP.CODE.like(keyword, !keyword.isEmpty()).or(PARAM_GROUP.NAME.like(keyword, !keyword.isEmpty()))
                        .or(PARAM_GROUP.NAME_EN.like(keyword, !keyword.isEmpty())))
                .orderBy(PARAM_GROUP.SORT.asc(), PARAM_GROUP.ID.asc());
    }

    public ParamGroup group(Long id) {
        return names(List.of(requireGroup(id, false))).getFirst();
    }

    ParamCatalogModels.Group catalogGroup(long id) {
        return ParamCatalogModels.Group.from(requireCatalogRecord(findGroup(id, false)));
    }

    public List<ParamGroup> groupOrder() {
        if (groups.selectCountByQuery(QueryWrapper.create()) > 5000) {
            throw BadRequestException.localized("error.param.group.sort.limit");
        }
        return names(groups.selectListByQuery(QueryWrapper.create()
                .orderBy(PARAM_GROUP.SORT.asc(), PARAM_GROUP.ID.asc())));
    }

    /**
     * 按完整分类列表更新排序。先锁定待办维度槽位分类，再按主键顺序锁定其他分类。
     *
     * @param request 按目标顺序排列的全部分类 ID
     * @throws BadRequestException 请求含重复 ID 或与当前分类集合不一致
     */
    @Audited(AuditOperation.PARAM_GROUP_SORT)
    @Transactional(rollbackFor = Exception.class)
    public void sortGroups(ParamSortRequest request) {
        todoDimensions.slotGroup(true);
        List<ParamGroup> records = groups.selectListByQuery(QueryWrapper.create()
                .orderBy(PARAM_GROUP.ID.asc()).forUpdate());
        if (writeSortChanges(records, request, groups::updateSort)) {
            log.info("参数分类顺序已写入，requestId={} groupCount={}",
                    AuditParticipants.currentRequestId(), records.size());
        }
    }

    /**
     * 校验分类结构并创建分类，初始版本用于区分该组的缓存结果。
     *
     * @param request 分类属性和字段结构
     * @return 带持久化标识的分类
     * @throws BadRequestException 分类编码重复或结构无效
     */
    @Audited(AuditOperation.PARAM_GROUP_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public ParamGroup createGroup(ParamGroupRequest request) {
        TodoDimensionParameters.requireWritePermission(request.getCode());
        TodoDimensionParameters.requireMutableStructure(request.getCode());
        DocumentDimensions.requireMutableGroup(request.getCode());
        validator.schema(request.getFieldConfig());
        if (groups.selectOneByQuery(QueryWrapper.create().where(PARAM_GROUP.CODE.eq(request.getCode())).forUpdate()) != null) {
            throw BadRequestException.localized("error.param.group.code.exists");
        }
        ParamGroup group = new ParamGroup();
        copyWriteRequest(request, group);
        group.setVersion(0L);
        created(group);
        groups.insert(group);
        log.info("参数分类已创建，requestId={} groupId={}", AuditParticipants.currentRequestId(), group.getId());
        return group(group.getId());
    }

    /**
     * 更新分类属性及字段结构。结构变更须兼容存量明细，并遵守系统固定分类约束。
     *
     * @param id      参数分类 ID
     * @param request 更新后的分类配置
     * @return 新版本的分类
     * @throws BadRequestException 分类不存在或新配置违反结构约束
     */
    @Audited(AuditOperation.PARAM_GROUP_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public ParamGroup updateGroup(Long id, ParamGroupRequest request) {
        ParamGroup group = requireGroup(id, true);
        TodoDimensionParameters.requireWritePermission(group.getCode());
        immutable(group, request);
        var config = request.getFieldConfig();
        if (TodoDimensionParameters.SLOT_GROUP.equals(group.getCode())) {
            if (!Boolean.TRUE.equals(request.getEnable())) {
                throw BadRequestException.localized("error.todo.dimension.fixed");
            }
            config = group.getFieldConfig();
        }
        if (DocumentDimensions.SLOT_GROUP.equals(group.getCode())) {
            if (!Boolean.TRUE.equals(request.getEnable())) {
                throw BadRequestException.localized("error.document.dimension.fixed");
            }
            config = group.getFieldConfig();
        }
        validator.schema(config);
        if (!Objects.equals(group.getFieldConfig(), config)) {
            validator.change(group.getFieldConfig(), config, currentItems(id));
        }
        copyWriteRequest(request, group);
        group.setFieldConfig(config);
        touchGroup(group);
        log.info("参数分类已更新，requestId={} groupId={} version={}",
                AuditParticipants.currentRequestId(), id, group.getVersion());
        return group(id);
    }

    /**
     * 删除没有明细、没有维度绑定且允许修改结构的分类。
     *
     * @param id 参数分类 ID
     * @throws BadRequestException 分类不存在、仍被使用或受固定结构保护
     */
    @Audited(AuditOperation.PARAM_GROUP_DELETE)
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long id) {
        ParamGroup current = requireGroup(id, false);
        TodoDimensionParameters.requireWritePermission(current.getCode());
        TodoDimensionParameters.requireMutableStructure(current.getCode());
        DocumentDimensions.requireMutableGroup(current.getCode());
        ParamGroup slotGroup = TodoDimensionParameters.isDictionary(current.getCode())
                ? todoDimensions.slotGroup(true) : null;
        requireGroup(id, true);
        todoDimensions.requireUnbound(slotGroup, id);
        documentDimensions.requireUnbound(current.getCode());
        if (!items.selectListByQuery(QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(id))
                .orderBy(PARAM_ITEM.ID.asc()).limit(1).forUpdate()).isEmpty()) {
            throw BadRequestException.localized("error.param.group.has-items");
        }
        groups.deleteById(id);
        log.info("参数分类已删除，requestId={} groupId={}", AuditParticipants.currentRequestId(), id);
    }

    public Page<ParamItem> items(Long groupId, ParamPageRequest request) {
        requireGroup(groupId, false);
        var page = items.paginate(request.toPage(), itemPageQuery(groupId, request));
        names(page.getRecords());
        return Page.convert(page);
    }

    Page<ParamCatalogModels.Item> catalogItems(long groupId, ParamPageRequest request) {
        requireCatalogRecord(findGroup(groupId, false));
        var page = items.paginate(request.toPage(), itemPageQuery(groupId, request));
        return catalogPage(page, ParamCatalogModels.Item::from);
    }

    private QueryWrapper itemPageQuery(Long groupId, ParamPageRequest request) {
        String keyword = keyword(request);
        return itemQuery(groupId).and(PARAM_ITEM.CODE.like(keyword, !keyword.isEmpty())
                .or(PARAM_ITEM.NAME.like(keyword, !keyword.isEmpty()))
                .or(PARAM_ITEM.NAME_EN.like(keyword, !keyword.isEmpty())));
    }

    public ParamItem item(Long groupId, Long id) {
        requireGroup(groupId, false);
        return names(List.of(requireItem(groupId, id))).getFirst();
    }

    ParamCatalogModels.Item catalogItem(long groupId, long id) {
        requireCatalogRecord(findGroup(groupId, false));
        return ParamCatalogModels.Item.from(requireCatalogRecord(findItem(groupId, id, false)));
    }

    /**
     * 按分类约束补齐默认值并新增明细，同时递增分类版本。
     *
     * @param groupId 所属分类 ID
     * @param request 明细属性和字段值
     * @return 新写入的参数明细
     */
    @Audited(AuditOperation.PARAM_ITEM_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public ParamItem createItem(Long groupId, ParamItemRequest request) {
        ParamGroup group = requireGroup(groupId, true);
        TodoDimensionParameters.requireWritePermission(group.getCode());
        TodoDimensionParameters.requireMutableStructure(group.getCode());
        DocumentDimensions.requireMutableItems(group.getCode());
        if (items.selectOneByQuery(QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(groupId)
                .and(PARAM_ITEM.CODE.eq(request.getCode()))).forUpdate()) != null) {
            throw BadRequestException.localized("error.param.item.code.exists");
        }
        ParamItem item = new ParamItem();
        copyWriteRequest(request, item);
        item.setGroupId(groupId);
        defaults(group.getFieldConfig(), item);
        validator.values(group.getFieldConfig(), item);
        validateTradingMarket(group, item);
        created(item);
        items.insert(item);
        touchGroup(group);
        log.info("参数明细已创建，requestId={} groupId={} itemId={} groupVersion={}",
                AuditParticipants.currentRequestId(), groupId, item.getId(), group.getVersion());
        return item(groupId, item.getId());
    }

    /**
     * 更新同一分类下的明细，校验维度槽位绑定和交易市场等系统规则，并递增分类版本。
     *
     * @param groupId 所属分类 ID
     * @param id      明细 ID
     * @param request 更新后的明细配置
     * @return 写入后的参数明细
     */
    @Audited(AuditOperation.PARAM_ITEM_UPDATE)
    @Transactional(rollbackFor = Exception.class)
    public ParamItem updateItem(Long groupId, Long id, ParamItemRequest request) {
        ParamGroup group = requireGroup(groupId, true);
        TodoDimensionParameters.requireWritePermission(group.getCode());
        ParamItem item = requireItem(groupId, id, true);
        immutable(item, request);
        boolean slot = TodoDimensionParameters.SLOT_GROUP.equals(group.getCode());
        Long previousDictionary = slot ? TodoDimensionParameters.dictionaryId(item) : null;
        boolean bindingLocked = slot && TodoDimensionParameters.isBindingLocked(item);
        String previousType = item.getValue1();
        String previousBinding = item.getValue2();
        copyWriteRequest(request, item);
        validator.values(group.getFieldConfig(), item);
        if (slot) {
            todoDimensions.updateSlot(item, previousDictionary, bindingLocked);
        }
        if (DocumentDimensions.SLOT_GROUP.equals(group.getCode())) {
            documentDimensions.updateSlot(item, previousType, previousBinding);
        }
        validateTradingMarket(group, item);
        updated(item);
        items.update(item, false);
        touchGroup(group);
        log.info("参数明细已更新，requestId={} groupId={} itemId={} groupVersion={}",
                AuditParticipants.currentRequestId(), groupId, id, group.getVersion());
        return item(groupId, id);
    }

    /**
     * 校验明细及当前引用关系，删除后同步递增所属分类版本。
     *
     * @param groupId 所属分类 ID
     * @param id      明细 ID
     * @throws BadRequestException 明细不存在、仍被使用或受固定结构保护
     */
    @Audited(AuditOperation.PARAM_ITEM_DELETE)
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long groupId, Long id) {
        ParamGroup group = requireGroup(groupId, true);
        TodoDimensionParameters.requireWritePermission(group.getCode());
        TodoDimensionParameters.requireMutableStructure(group.getCode());
        DocumentDimensions.requireMutableItems(group.getCode());
        requireItem(groupId, id, true);
        documentDimensions.requireUnreferenced(group.getCode(), id);
        if (TRADING_MARKET.equals(group.getCode()) && items.hasCalendarDays(id)) {
            throw BadRequestException.localized("error.param.item.trading-calendar.in-use");
        }
        if ((TodoDimensionParameters.isDictionary(group.getCode()) || TRADING_MARKET.equals(group.getCode()))
                && items.hasTodoReferences(id)) {
            throw BadRequestException.localized("error.todo.dimension.option-in-use");
        }
        items.deleteById(id);
        touchGroup(group);
        log.info("参数明细已删除，requestId={} groupId={} itemId={} groupVersion={}",
                AuditParticipants.currentRequestId(), groupId, id, group.getVersion());
    }

    private static void validateTradingMarket(ParamGroup group, ParamItem item) {
        if (!TRADING_MARKET.equals(group.getCode())) {
            return;
        }
        if (!TRADING_MARKET_CODE.matcher(item.getCode()).matches()) {
            throw BadRequestException.localized("error.param.item.trading-calendar.code");
        }
        String timeZone = item.getValue1();
        if (timeZone == null) {
            throw BadRequestException.localized("error.param.item.trading-calendar.time-zone");
        }
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw BadRequestException.localized("error.param.item.trading-calendar.time-zone");
        }
        if (item.getValue2() == null || !TRADING_MARKET_ICON.matcher(item.getValue2()).matches()) {
            throw BadRequestException.localized("error.param.item.trading-calendar.icon");
        }
    }

    public List<ParamItem> order(Long groupId) {
        requireGroup(groupId, false);
        if (items.selectCountByCondition(PARAM_ITEM.GROUP_ID.eq(groupId)) > 5000) {
            throw BadRequestException.localized("error.param.item.sort.limit");
        }
        return names(allItems(groupId));
    }

    /**
     * 按完整明细列表更新分类内的顺序，序号发生变化时同步递增分类版本。
     *
     * @param groupId 所属分类 ID
     * @param request 按目标顺序排列的全部明细 ID
     * @throws BadRequestException 分类不存在、列表含重复 ID 或与当前明细集合不一致
     */
    @Audited(AuditOperation.PARAM_ITEM_SORT)
    @Transactional(rollbackFor = Exception.class)
    public void sort(Long groupId, ParamSortRequest request) {
        ParamGroup group = requireGroup(groupId, true);
        TodoDimensionParameters.requireWritePermission(group.getCode());
        if (writeSortChanges(currentItems(groupId), request, items::updateSort)) {
            touchGroup(group);
            log.info("参数明细顺序已写入，requestId={} groupId={} groupVersion={}",
                    AuditParticipants.currentRequestId(), groupId, group.getVersion());
        }
    }

    private boolean writeSortChanges(List<? extends ParamRecord> records, ParamSortRequest request, SortWriter writer) {
        Set<Long> ids = new HashSet<>(request.getIds());
        Set<Long> existing = records.stream().map(ParamRecord::getId).collect(Collectors.toSet());
        if (ids.size() != request.getIds().size() || !ids.equals(existing)) {
            throw BadRequestException.localized("error.param.sort.invalid");
        }
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < request.getIds().size(); i++) {
            order.put(request.getIds().get(i), i + 1);
        }
        // 继续按锁定记录的主键顺序访问，只写实际发生变化的序号。
        Map<Long, Integer> changed = new LinkedHashMap<>();
        for (ParamRecord item : records) {
            Integer position = order.get(item.getId());
            if (!position.equals(item.getSort())) {
                changed.put(item.getId(), position);
            }
        }
        if (changed.isEmpty()) {
            return false;
        }
        long actor = StpUtil.getLoginIdAsLong();
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        writeSortBatches(changed, batch -> writer.update(batch, actor, now));
        return true;
    }

    private static void writeSortBatches(Map<Long, Integer> changed, Consumer<Map<Long, Integer>> writer) {
        Map<Long, Integer> batch = new LinkedHashMap<>();
        for (var entry : changed.entrySet()) {
            batch.put(entry.getKey(), entry.getValue());
            if (batch.size() == SORT_BATCH_SIZE) {
                writer.accept(batch);
                batch = new LinkedHashMap<>();
            }
        }
        if (!batch.isEmpty()) {
            writer.accept(batch);
        }
    }

    @FunctionalInterface
    private interface SortWriter {

        void update(Map<Long, Integer> positions, long actor, LocalDateTime now);
    }

    /**
     * 查询界面可用的启用明细，按显示顺序返回并使用带版本的选项缓存。
     *
     * @param code 参数分类编码
     * @return 选项 JSON 数组，分类缺失或已停用时返回空数组
     */
    public JsonNode options(String code) {
        ParamGroup group = groups.selectOneByCondition(PARAM_GROUP.CODE.eq(code));
        if (group == null || !Boolean.TRUE.equals(group.getEnable())) {
            return json.createArrayNode();
        }
        return cache.get(group, () -> {
            var result = json.createArrayNode();
            for (ParamItem item : items.selectListByQuery(itemQuery(group.getId()).and(PARAM_ITEM.ENABLE.eq(true)))) {
                ObjectNode row = json.createObjectNode();
                row.put("code", item.getCode());
                row.put("name", item.getName());
                row.put("nameEn", item.getNameEn());
                for (int i = 1; i <= 9; i++) {
                    row.put("value" + i, item.value(i));
                }
                row.set("extra", item.getExtra());
                result.add(row);
            }
            return result;
        });
    }

    private ParamGroup requireGroup(Long id, boolean lock) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.param.group.id.invalid");
        }
        ParamGroup group = findGroup(id, lock);
        if (group == null) {
            throw BadRequestException.localized("error.param.group.missing");
        }
        return group;
    }

    private ParamGroup findGroup(Long id, boolean lock) {
        QueryWrapper query = QueryWrapper.create().where(PARAM_GROUP.ID.eq(id));
        return groups.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    private ParamItem requireItem(Long groupId, Long id) {
        return requireItem(groupId, id, false);
    }

    private ParamItem requireItem(Long groupId, Long id, boolean lock) {
        ParamItem item = findItem(groupId, id, lock);
        if (item == null) {
            throw BadRequestException.localized("error.param.item.missing");
        }
        return item;
    }

    private ParamItem findItem(Long groupId, Long id, boolean lock) {
        ParamItem item = lock ? items.selectOneByQuery(QueryWrapper.create().where(PARAM_ITEM.ID.eq(id)).forUpdate())
                : items.selectOneById(id);
        return item != null && item.getGroupId().equals(groupId) ? item : null;
    }

    private static <T extends ParamRecord> T requireCatalogRecord(T row) {
        if (row == null) {
            throw new ResourceNotFoundException();
        }
        return row;
    }

    private static <T, R> Page<R> catalogPage(com.mybatisflex.core.paginate.Page<T> source, Function<T, R> mapper) {
        var page = new Page<R>();
        page.setTotal(source.getTotalRow());
        page.setPageData(source.getRecords().stream().map(mapper).toList());
        return page;
    }

    private QueryWrapper itemQuery(Long groupId) {
        return QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(groupId))
                .orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc());
    }

    private List<ParamItem> allItems(Long groupId) {
        return items.selectListByQuery(itemQuery(groupId));
    }

    private List<ParamItem> currentItems(Long groupId) {
        return items.selectListByQuery(QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(groupId))
                .orderBy(PARAM_ITEM.ID.asc()).forUpdate());
    }

    private String keyword(ParamPageRequest request) {
        return request.getKeyword() == null ? "" : request.getKeyword().trim();
    }

    private void copyWriteRequest(ParamWriteRequest request, ParamRecord row) {
        BeanUtils.copyProperties(request, row, "nameEn");
        if (request.getNameEn() != null) {
            row.setNameEn(normalizeEnglishName(request.getNameEn()));
        }
    }

    private void immutable(ParamRecord row, ParamWriteRequest request) {
        if (!row.getCode().equals(request.getCode())) {
            throw BadRequestException.localized("error.param.code.immutable");
        }
    }

    private void created(ParamRecord row) {
        updated(row);
        row.setCreatedBy(row.getUpdatedBy());
        row.setCreatedAt(row.getUpdatedAt());
    }

    private void updated(ParamRecord row) {
        row.setUpdatedBy(StpUtil.getLoginIdAsLong());
        row.setUpdatedAt(LocalDateTime.now(ZoneId.systemDefault()));
    }

    /**
     * 在写入事务内递增分类版本，使 {@link ParamLookupCache} 的后续查询切换到新键。
     */
    private void touchGroup(ParamGroup group) {
        group.setVersion(group.getVersion() + 1);
        updated(group);
        groups.update(group, false);
    }

    private <T extends ParamRecord> List<T> names(List<T> records) {
        Set<Long> ids = new HashSet<>();
        records.forEach(row -> {
            if (row.getCreatedBy() != null) {
                ids.add(row.getCreatedBy());
            }
            if (row.getUpdatedBy() != null) {
                ids.add(row.getUpdatedBy());
            }
        });
        Map<Long, String> names = ids.isEmpty() ? Map.of() : users.selectListByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        records.forEach(row -> {
            row.setCreatedByName(actor(row.getCreatedBy(), names));
            row.setUpdatedByName(actor(row.getUpdatedBy(), names));
        });
        return records;
    }

    private String actor(Long id, Map<Long, String> names) {
        return id == null ? "系统初始化" : names.getOrDefault(id, "已删除用户（" + id + "）");
    }

    private void defaults(JsonNode config, ParamItem item) {
        if (config == null || config.isNull()) {
            return;
        }
        fixedDefaults(config.path("fixedFields"), item);
        if (item.getExtra() == null || item.getExtra().isNull()) {
            item.setExtra(json.createObjectNode());
        }
        if (item.getExtra() instanceof ObjectNode extra) {
            for (JsonNode field : config.path("extraFields")) {
                String key = field.path("key").asString();
                if (!extra.has(key) && field.has(DEFAULT_VALUE)) {
                    extra.set(key, field.get(DEFAULT_VALUE));
                }
            }
        }
    }

    private void fixedDefaults(JsonNode fixed, ParamItem item) {
        BeanWrapperImpl bean = new BeanWrapperImpl(item);
        for (var field : fixed.properties()) {
            if (bean.getPropertyValue(field.getKey()) == null && field.getValue().has(DEFAULT_VALUE)) {
                JsonNode value = field.getValue().get(DEFAULT_VALUE);
                if (!value.isNull()) {
                    bean.setPropertyValue(field.getKey(), value.isString() ? value.asString() : value.toString());
                }
            }
        }
    }
}
