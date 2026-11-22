package me.lendev.flow.param.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryTable;
import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import me.lendev.flow.param.domain.ParamItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 参数明细 Mapper，继承 MyBatis-Flex 通用 CRUD 能力。
 */
public interface ParamItemMapper extends BaseMapper<ParamItem> {

    /**
     * 批量更新已锁定明细的序号和更新人信息。
     */
    @Update("<script>UPDATE sys_param_item " + ParamSortSql.SET_ORDER)
    void updateSort(@Param("positions") Map<Long, Integer> positions, @Param("actor") long actor,
                    @Param("now") LocalDateTime now);

    QueryTable DOCUMENT = new QueryTable("doc_document");
    QueryColumn DOCUMENT_ID = new QueryColumn(DOCUMENT, "id");
    QueryColumn DOCUMENT_PRODUCT_ID = new QueryColumn(DOCUMENT, "productId");
    List<QueryColumn> DOCUMENT_DIMENSIONS = List.of(new QueryColumn(DOCUMENT, "dimension1Value"),
            new QueryColumn(DOCUMENT, "dimension2Value"), new QueryColumn(DOCUMENT, "dimension3Value"),
            new QueryColumn(DOCUMENT, "dimension4Value"), new QueryColumn(DOCUMENT, "dimension5Value"),
            new QueryColumn(DOCUMENT, "dimension6Value"));

    /**
     * 判断是否存在使用指定维度槽位的文档，包含逻辑删除的历史文档。
     */
    default boolean hasDocumentDimensionValues(int slot) {
        return hasDocumentReferences(DOCUMENT_DIMENSIONS.get(slot - 1).isNotNull());
    }

    /**
     * 判断指定维度是否引用该字典选项。调用方须先锁定文档维度分类。
     */
    default boolean hasDocumentOptionReferences(int slot, long itemId) {
        return hasDocumentReferences(DOCUMENT_DIMENSIONS.get(slot - 1).eq(Long.toString(itemId)));
    }

    /**
     * 判断是否有文档使用该产品。调用方须先锁定文档维度分类。
     */
    default boolean hasDocumentProductReferences(long itemId) {
        return hasDocumentReferences(DOCUMENT_PRODUCT_ID.eq(itemId));
    }

    private boolean hasDocumentReferences(QueryCondition condition) {
        // 锁定读使用最新已提交数据，避免使用事务早先的快照漏掉刚提交的文档。
        return selectObjectByQueryAs(QueryWrapper.create().select(DOCUMENT_ID).from(DOCUMENT)
                .where(condition).limit(1).forUpdate(), Long.class) != null;
    }

    /**
     * 判断交易市场参数项是否已有日历记录，调用方应先锁定该参数项。
     */
    @Select("SELECT EXISTS (SELECT 1 FROM sys_trading_calendar_day WHERE marketItemId = #{itemId})")
    boolean hasCalendarDays(@Param("itemId") long itemId);

    /**
     * 判断参数项是否已被待办、周期或流程引用；调用方先锁定所属分类和参数项。
     */
    @Select("""
            SELECT EXISTS (SELECT 1 FROM wb_todo WHERE marketItemId = #{itemId}
                    OR dimension1ItemId = #{itemId} OR dimension2ItemId = #{itemId} OR dimension3ItemId = #{itemId}
                    OR dimension4ItemId = #{itemId} OR dimension5ItemId = #{itemId})
                OR EXISTS (SELECT 1 FROM wb_todo_series WHERE marketItemId = #{itemId}
                    OR dimension1ItemId = #{itemId} OR dimension2ItemId = #{itemId} OR dimension3ItemId = #{itemId}
                    OR dimension4ItemId = #{itemId} OR dimension5ItemId = #{itemId})
                OR EXISTS (SELECT 1 FROM wf_instance WHERE
                    dimension1ItemId = #{itemId} OR dimension2ItemId = #{itemId} OR dimension3ItemId = #{itemId}
                    OR dimension4ItemId = #{itemId} OR dimension5ItemId = #{itemId})
            """)
    boolean hasTodoReferences(@Param("itemId") long itemId);
}

