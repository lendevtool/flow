package me.lendev.flow.field.mapper;

import static com.mybatisflex.core.query.QueryMethods.case_;
import static com.mybatisflex.core.query.QueryMethods.raw;
import static me.lendev.flow.field.persistence.table.FieldDefinitionTableDef.FIELD_DEFINITION;
import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import java.util.List;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import me.lendev.flow.field.FieldModels.DataType;
import me.lendev.flow.field.persistence.FieldDefinition;
import me.lendev.flow.param.domain.ParamGroup;
import me.lendev.flow.param.domain.ParamItem;

/**
 * 字段定义及其字典选项的持久化映射。
 */
public interface FieldMapper extends BaseMapper<FieldDefinition> {

    default Page<FieldDefinition> page(String keyword, DataType dataType, Boolean enable, int pageNo, int size) {
        var query = QueryWrapper.create()
                .where(FIELD_DEFINITION.DATA_TYPE.eq(dataType == null ? null : dataType.name(), dataType != null))
                .and(FIELD_DEFINITION.ENABLE.eq(enable, enable != null))
                .orderBy(FIELD_DEFINITION.ID.desc());
        if (keyword != null) {
            query.and(nameMatches(keyword));
        }
        return paginate(pageNo, size, query);
    }

    default FieldDefinition find(long id, boolean lock) {
        var query = QueryWrapper.create().where(FIELD_DEFINITION.ID.eq(id));
        return selectOneByQuery(lock ? query.forUpdate() : query);
    }

    default ParamGroup dictionary(long id, boolean lock) {
        var query = dictionaryColumns().where(PARAM_GROUP.ID.eq(id));
        return selectOneByQueryAs(lock ? query.forUpdate() : query, ParamGroup.class);
    }

    default List<ParamGroup> dictionaries(String keyword, Long selectedId) {
        var query = dictionaryColumns().where(PARAM_GROUP.ENABLE.eq(true).and(nameMatches(keyword)));
        if (selectedId != null) {
            var selected = PARAM_GROUP.ID.eq(selectedId);
            query.or(selected).orderBy(case_().when(PARAM_GROUP.ID.eq(selectedId)).then(0).else_(1).end().asc());
        }
        return selectListByQueryAs(query.orderBy(PARAM_GROUP.SORT.asc(), PARAM_GROUP.ID.asc()).limit(50), ParamGroup.class);
    }

    default List<ParamItem> options(long groupId, boolean lock) {
        var query = QueryWrapper.create().select(PARAM_ITEM.CODE, PARAM_ITEM.NAME, PARAM_ITEM.NAME_EN, PARAM_ITEM.ENABLE)
                .from(PARAM_ITEM).where(PARAM_ITEM.GROUP_ID.eq(groupId)).orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc());
        return selectListByQueryAs(lock ? query.forUpdate() : query, ParamItem.class);
    }

    default List<ParamItem> metadata(String code) {
        var enabled = case_().when(PARAM_ITEM.ENABLE.eq(true).and(PARAM_GROUP.ENABLE.eq(true)))
                .then(1).else_(0).end().as("enable");
        var query = QueryWrapper.create().select(PARAM_ITEM.CODE, PARAM_ITEM.NAME, PARAM_ITEM.NAME_EN, enabled)
                .from(PARAM_ITEM).join(PARAM_GROUP).on(PARAM_ITEM.GROUP_ID.eq(PARAM_GROUP.ID))
                .where(PARAM_GROUP.CODE.eq(code)).orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc());
        return selectListByQueryAs(query, ParamItem.class);
    }

    private static QueryWrapper dictionaryColumns() {
        return QueryWrapper.create().select(PARAM_GROUP.ID, PARAM_GROUP.CODE, PARAM_GROUP.NAME, PARAM_GROUP.NAME_EN,
                PARAM_GROUP.ENABLE).from(PARAM_GROUP);
    }

    private static QueryCondition nameMatches(String keyword) {
        return raw("""
                (LOWER(code) LIKE LOWER(?) ESCAPE '!' OR LOWER(name) LIKE LOWER(?) ESCAPE '!'
                    OR LOWER(nameEn) LIKE LOWER(?) ESCAPE '!')
                """, keyword, keyword, keyword);
    }
}
