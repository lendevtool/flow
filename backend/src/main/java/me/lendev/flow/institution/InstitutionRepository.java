package me.lendev.flow.institution;

import static me.lendev.flow.institution.entity.table.InstitutionRowTableDef.INSTITUTION_ROW;
import static me.lendev.flow.institution.entity.table.InstitutionChangeRowTableDef.INSTITUTION_CHANGE_ROW;
import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.institution.InstitutionModels.ChangeStatus;
import me.lendev.flow.institution.InstitutionModels.Query;
import me.lendev.flow.institution.entity.InstitutionChangeRow;
import me.lendev.flow.institution.entity.InstitutionRow;
import me.lendev.flow.institution.mapper.InstitutionChangeMapper;
import me.lendev.flow.institution.mapper.InstitutionMapper;
import me.lendev.flow.param.domain.ParamItem;
import me.lendev.flow.param.mapper.ParamGroupMapper;
import me.lendev.flow.param.mapper.ParamItemMapper;
import org.springframework.stereotype.Repository;

/**
 * 机构档案、变更申请及字典的持久化查询。
 */
@Repository
@RequiredArgsConstructor
class InstitutionRepository {

    static final String TYPE_GROUP = "INSTITUTION_TYPE";
    static final String COUNTRY_GROUP = "INSTITUTION_COUNTRY_REGION";

    private final InstitutionMapper institutions;
    private final InstitutionChangeMapper changes;
    private final ParamGroupMapper groups;
    private final ParamItemMapper items;

    InstitutionRow institution(long id, boolean lock) {
        var query = QueryWrapper.create().where(INSTITUTION_ROW.ID.eq(id));
        return institutions.selectOneByQuery(lock ? query.forUpdate() : query);
    }

    Page<InstitutionRow> page(Query filter) {
        var query = QueryWrapper.create()
                .where(INSTITUTION_ROW.TYPE_ITEM_ID.eq(filter.typeItemId()))
                .and(INSTITUTION_ROW.COUNTRY_REGION_ITEM_ID.eq(filter.countryRegionItemId()))
                .and(INSTITUTION_ROW.ENABLED.eq(filter.enabled()));
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String escaped = "%" + filter.keyword().strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.and("(code LIKE ? ESCAPE '!' OR nameZh LIKE ? ESCAPE '!' OR nameEn LIKE ? ESCAPE '!')",
                    escaped, escaped, escaped);
        }
        return Page.convert(institutions.paginate(filter.pageNo() == null ? 1 : filter.pageNo(),
                filter.pageSize() == null ? 20 : filter.pageSize(), query.orderBy(INSTITUTION_ROW.ID.desc())));
    }

    void insert(InstitutionRow row) {
        institutions.insertSelective(row);
    }

    void update(InstitutionRow row) {
        institutions.update(row, false);
    }

    InstitutionChangeRow change(long id) {
        return changes.selectOneById(id);
    }

    InstitutionChangeRow changeForResult(long id) {
        return changes.selectOneByQuery(QueryWrapper.create().where(INSTITUTION_CHANGE_ROW.ID.eq(id)).forUpdate());
    }

    Page<InstitutionChangeRow> changes(long institution, int page, int size) {
        return Page.convert(changes.paginate(page, size, QueryWrapper.create()
                .where(INSTITUTION_CHANGE_ROW.INSTITUTION_ID.eq(institution))
                .orderBy(INSTITUTION_CHANGE_ROW.ID.desc())));
    }

    boolean pending(long institution) {
        // 发起方已锁定机构行。锁定读也能看到并发事务最新提交的申请。
        return changes.selectOneByQuery(QueryWrapper.create()
                .where(INSTITUTION_CHANGE_ROW.INSTITUTION_ID.eq(institution))
                .and(INSTITUTION_CHANGE_ROW.STATUS.eq(ChangeStatus.PENDING.name()))
                .limit(1).forUpdate()) != null;
    }

    void insert(InstitutionChangeRow row) {
        changes.insertSelective(row);
    }

    void update(InstitutionChangeRow row) {
        changes.update(row, false);
    }

    Dictionary dictionary(String code, boolean lock) {
        var groupQuery = QueryWrapper.create().where(PARAM_GROUP.CODE.eq(code));
        var group = groups.selectOneByQuery(lock ? groupQuery.forUpdate() : groupQuery);
        if (group == null) {
            return new Dictionary(null, false, List.of());
        }
        var itemQuery = QueryWrapper.create().where(PARAM_ITEM.GROUP_ID.eq(group.getId()))
                .orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc());
        return new Dictionary(group.getId(), Boolean.TRUE.equals(group.getEnable()),
                items.selectListByQuery(lock ? itemQuery.forUpdate() : itemQuery));
    }

    record Dictionary(Long id, boolean enabled, List<ParamItem> items) {
    }
}
