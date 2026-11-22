package me.lendev.flow.workbench.todo;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.util.MultiValueMap;

/**
 * 将公共查询参数规范化为列表、日历和统计共用的条件。
 */
record TodoQuery(String scope, String keyword, String origin, String status, String dateField,
                 LocalDate from, LocalDate to, int page, int size, boolean includeCompleted, boolean calendarJump,
                 Map<Integer, List<Long>> publicFilters, Map<Integer, List<String>> personalFilters) {

    private static final Set<String> SCOPES = Set.of("mine", "created", "done", "all");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "DONE", "CANCELLED",
            "OVERDUE", "TODAY", "UNSCHEDULED");
    private static final Set<String> ORIGINS = Set.of("MANUAL", "BUSINESS", "SERIES", "FLOW");

    /**
     * 解析并校验待办的公共查询参数。
     *
     * @param values HTTP 查询参数
     * @return 规范化的查询条件，缺省 scope=mine、page=1、size=20
     * @throws BadRequestException 参数越界、枚举无效或日期区间倒置
     */
    static TodoQuery from(MultiValueMap<String, String> values) {
        try {
            String scope = string(values, "scope", "mine");
            String keyword = string(values, "keyword", "");
            String origin = string(values, "origin", "");
            String status = string(values, "status", "");
            String dateField = string(values, "dateField", "due");
            String include = string(values, "includeCompleted", "false");
            LocalDate from = date(values, "fromDate");
            LocalDate to = date(values, "toDate");
            int page = Integer.parseInt(string(values, "page", "1"));
            int size = Integer.parseInt(string(values, "size", "20"));
            if (!SCOPES.contains(scope) || keyword.length() > 200 || !origin.isEmpty() && !ORIGINS.contains(origin)
                    || !status.isEmpty() && !STATUSES.contains(status) || !Set.of("due", "plan").contains(dateField)
                    || !Set.of("true", "false").contains(include) || from != null && to != null && from.isAfter(to)
                    || page < 1 || size < 1 || size > 100) {
                throw invalid();
            }
            var filters = dimensionFilters(values);
            return new TodoQuery(scope, keyword, origin, status, dateField, from, to, page, size,
                    Boolean.parseBoolean(include), status.equals("UNSCHEDULED") && values.containsKey("includeCompleted"),
                    filters.publicFilters(), filters.personalFilters());
        } catch (java.time.DateTimeException | NumberFormatException malformed) {
            throw invalid();
        }
    }

    private record DimensionFilters(Map<Integer, List<Long>> publicFilters,
                                    Map<Integer, List<String>> personalFilters) {

    }

    private static DimensionFilters dimensionFilters(MultiValueMap<String, String> values) {
        Map<Integer, List<Long>> publicFilters = new LinkedHashMap<>();
        Map<Integer, List<String>> personalFilters = new LinkedHashMap<>();
        for (int slot = 1; slot <= 5; slot++) {
            var publicValues = selections(values, "public" + slot, 36);
            var personalValues = selections(values, "personal" + slot, TodoPersonalDimensions.MAX_VALUE_LENGTH);
            if (!publicValues.isEmpty()) {
                var ids = publicValues.stream().map(Long::valueOf).toList();
                if (ids.stream().anyMatch(id -> id < 1)) {
                    throw invalid();
                }
                publicFilters.put(slot, ids);
            }
            if (!personalValues.isEmpty()) {
                personalFilters.put(slot, personalValues);
            }
        }
        return new DimensionFilters(publicFilters, personalFilters);
    }

    private static LocalDate date(MultiValueMap<String, String> values, String name) {
        String value = string(values, name, "");
        if (value.isEmpty()) {
            return null;
        }
        var date = LocalDate.parse(value);
        if (date.getYear() < 1000 || date.getYear() > 9999) {
            throw invalid();
        }
        return date;
    }

    private static List<String> selections(MultiValueMap<String, String> values, String name, int maxLength) {
        var list = values.getOrDefault(name, List.of());
        if (list.size() > 100 || list.stream().anyMatch(value -> value.isBlank() || value.length() > maxLength)) {
            throw invalid();
        }
        return list.stream().distinct().toList();
    }

    private static String string(MultiValueMap<String, String> values, String name, String fallback) {
        String value = values.getFirst(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static BadRequestException invalid() {
        return BadRequestException.localized("error.todo.query");
    }
}
