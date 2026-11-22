package me.lendev.flow.workbench.todo;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryTable;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * 场景声明的维度契约，从受控字段绑定筛选条件及选项投影。
 *
 * @param key        前端和接口使用的维度键
 * @param label      中文或本地化的显示名称
 * @param primary    是否放在顶部常用筛选区
 * @param multiple   是否允许多选
 * @param options    随元数据下发的低基数字典
 * @param validValue 校验值的格式
 * @param matches    将已校验的字典值列表绑定为同维度并集查询条件，搜索维度使用选项投影匹配
 * @param search     需要远程检索时声明的投影，字典维度为空
 */
public record BusinessTodoCalendarDimension(String key, String label, boolean primary, boolean multiple,
                                            List<Option> options, Predicate<String> validValue,
                                            Function<List<String>, QueryCondition> matches, Search search) {

    public BusinessTodoCalendarDimension {
        if (!BusinessTodoCalendarRequest.validKey(key) || label == null || label.isBlank()) {
            throw new IllegalArgumentException("业务日历维度键或名称无效");
        }
        options = List.copyOf(options);
        Objects.requireNonNull(validValue);
        if (search == null) {
            Objects.requireNonNull(matches);
        }
    }

    /**
     * 登记允许直接向场景查看者展示的字典选项。
     */
    public static BusinessTodoCalendarDimension dictionary(String key, String label, boolean primary, boolean multiple,
                                                           List<Option> options, Predicate<String> validValue,
                                                           Function<List<String>, QueryCondition> matches) {
        return new BusinessTodoCalendarDimension(key, label, primary, multiple, options, validValue, matches, null);
    }

    /**
     * 登记从当前有权查看的待办及其相关对象中查询的分页选项。
     */
    public static BusinessTodoCalendarDimension searchable(String key, String label, boolean primary, boolean multiple,
                                                           Predicate<String> validValue, Search search) {
        return new BusinessTodoCalendarDimension(key, label, primary, multiple, List.of(), validValue, null,
                Objects.requireNonNull(search));
    }

    /**
     * @param value   稳定筛选值
     * @param label   选项显示名称
     * @param enabled 是否可供新数据选择，停用历史值可用于筛选
     */
    public record Option(String value, String label, boolean enabled) {
    }

    /**
     * 选项投影基于已应用场景、上下文和单据权限的查询。
     *
     * @param value   稳定选项 ID 列
     * @param label   当前选项名称列，同一 ID 应返回同一名称
     * @param enabled 选项启停列，可空，缺省为启用
     * @param joins   根据查看人提供有序的内连接，用于联接选项表及限制可见选项，可空
     */
    public record Search(QueryColumn value, QueryColumn label, QueryColumn enabled,
                         IntFunction<List<OptionJoin>> joins) {
        public Search {
            Objects.requireNonNull(value);
            Objects.requireNonNull(label);
        }
    }

    /**
     * @param table 选项关联使用的固定表或表别名
     * @param on    将选项关联到当前任务的连接条件，可同时限制该对象的可见性
     */
    public record OptionJoin(QueryTable table, QueryCondition on) {
        public OptionJoin {
            Objects.requireNonNull(table);
            if (on == null || !on.checkEffective()) {
                throw new IllegalArgumentException("选项关联须提供有效的连接条件");
            }
        }
    }
}
