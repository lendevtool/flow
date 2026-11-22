package me.lendev.flow.workbench.todo;

import static com.mybatisflex.core.query.QueryMethods.exists;
import static com.mybatisflex.core.query.QueryMethods.number;
import static com.mybatisflex.core.query.QueryMethods.raw;
import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessContextTableDef.TODO_BUSINESS_CONTEXT;
import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessLinkTableDef.TODO_BUSINESS_LINK;
import static me.lendev.flow.workbench.todo.entity.table.TodoInstanceTableDef.TODO_INSTANCE;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

/**
 * 场景可重复使用的业务日历查询条件。
 */
public final class BusinessTodoCalendarConditions {

    private BusinessTodoCalendarConditions() {
    }

    /**
     * 仅当业务场景允许当前用户访问登记范围内所有记录时使用。
     */
    public static QueryCondition allVisible() {
        return raw("1 = 1");
    }

    /**
     * 明确拒绝所有记录，也用于处理没有任何可匹配筛选值的情况。
     */
    public static QueryCondition noneVisible() {
        return raw("1 = 0");
    }

    /**
     * 以相关子查询按归集对象匹配待办，避免一对多关系导致计数重复。
     */
    public static QueryCondition related(String module, String objectType, List<String> ids) {
        if (ids.isEmpty()) {
            return noneVisible();
        }
        var link = TODO_BUSINESS_LINK.as("calendar_filter_link");
        return exists(QueryWrapper.create().select(number(1)).from(link)
                .where(link.TODO_ID.eq(TODO_INSTANCE.ID)).and(link.REF_MODULE.eq(module, true))
                .and(link.REF_TYPE.eq(objectType, true)).and(link.REF_ID.in(ids)));
    }

    /**
     * 按场景登记的任务环节匹配。
     */
    public static QueryCondition taskKinds(List<String> codes) {
        return codes.isEmpty() ? noneVisible() : TODO_BUSINESS_CONTEXT.TASK_KIND_CODE.in(codes);
    }

    /**
     * 将登记的槽位映射到已有待办公共维度列。
     */
    public static QueryColumn publicDimension(int slot) {
        return switch (slot) {
            case 1 -> TODO_INSTANCE.DIMENSION1_ITEM_ID;
            case 2 -> TODO_INSTANCE.DIMENSION2_ITEM_ID;
            case 3 -> TODO_INSTANCE.DIMENSION3_ITEM_ID;
            case 4 -> TODO_INSTANCE.DIMENSION4_ITEM_ID;
            case 5 -> TODO_INSTANCE.DIMENSION5_ITEM_ID;
            default -> throw new IllegalArgumentException("待办公共维度槽位必须为 1 至 5");
        };
    }
}
