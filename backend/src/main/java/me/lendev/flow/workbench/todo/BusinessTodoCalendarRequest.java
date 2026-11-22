package me.lendev.flow.workbench.todo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.util.MultiValueMap;

/**
 * 解析业务日历窗口、状态和场景筛选参数。
 */
record BusinessTodoCalendarRequest(String contextId, Selection status, Window window, Page page,
                                   Map<String, List<String>> filters) {

    static final String CONTEXT_ID = "contextId";
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");
    private static final int MAX_VALUES = 50;

    enum Selection {ALL, PENDING, DONE, CANCELLED}

    record Window(LocalDate from, LocalDate to) {
        boolean unscheduled() {
            return from == null;
        }
    }

    record Page(int number, int size) {
        long offset() {
            return (number - 1L) * size;
        }
    }

    static BusinessTodoCalendarRequest calendar(MultiValueMap<String, String> parameters) {
        return from(parameters, range(parameters), null);
    }

    static BusinessTodoCalendarRequest items(MultiValueMap<String, String> parameters) {
        Window window = switch (required(parameters, "mode")) {
            case "range" -> range(parameters);
            case "day" -> {
                LocalDate day = date(required(parameters, "date"));
                yield new Window(day, day);
            }
            case "unscheduled" -> new Window(null, null);
            default -> throw invalid();
        };
        if ((window.unscheduled() || "day".equals(parameters.getFirst("mode")))
                && (parameters.containsKey("fromDate") || parameters.containsKey("toDate"))
                || !"day".equals(parameters.getFirst("mode")) && parameters.containsKey("date")) {
            throw invalid();
        }
        return from(parameters, window, page(parameters));
    }

    private static BusinessTodoCalendarRequest from(MultiValueMap<String, String> parameters, Window window, Page page) {
        String selection = single(parameters, "status");
        Selection status;
        try {
            status = selection == null ? Selection.ALL : Selection.valueOf(selection.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        Map<String, List<String>> filters = new LinkedHashMap<>();
        parameters.forEach((key, values) -> {
            if (!key.startsWith("filter.")) {
                return;
            }
            String field = key.substring("filter.".length());
            if (!validKey(field)) {
                throw invalid();
            }
            var unique = selections(values);
            if (!unique.isEmpty()) {
                filters.put(field, unique);
            }
        });
        return new BusinessTodoCalendarRequest(single(parameters, CONTEXT_ID), status, window, page,
                Collections.unmodifiableMap(filters));
    }

    static String single(MultiValueMap<String, String> values, String key) {
        var entries = values.get(key);
        if (entries == null) {
            return null;
        }
        if (entries.size() != 1) {
            throw invalid();
        }
        return entries.getFirst();
    }

    static List<String> selections(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_VALUES || values.stream().anyMatch(value -> !validValue(value))) {
            throw invalid();
        }
        return values.stream().distinct().toList();
    }

    private static String required(MultiValueMap<String, String> values, String key) {
        String value = single(values, key);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    static Page page(MultiValueMap<String, String> values) {
        try {
            String number = single(values, "page");
            String size = single(values, "size");
            int pageNumber = number == null ? 1 : Integer.parseInt(number);
            int pageSize = size == null ? 20 : Integer.parseInt(size);
            if (pageNumber < 1 || pageSize < 1 || pageSize > 100) {
                throw invalid();
            }
            return new Page(pageNumber, pageSize);
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
    }

    private static Window range(MultiValueMap<String, String> values) {
        LocalDate from = date(required(values, "fromDate"));
        LocalDate to = date(required(values, "toDate"));
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 42) {
            throw invalid();
        }
        return new Window(from, to);
    }

    private static LocalDate date(String value) {
        if (!DATE.matcher(value).matches()) {
            throw invalid();
        }
        try {
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() < 1000) {
                throw invalid();
            }
            return result;
        } catch (DateTimeParseException malformed) {
            throw invalid();
        }
    }

    static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    static boolean validValue(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= 128 && value.codePoints().noneMatch(Character::isISOControl);
    }

    static BadRequestException invalid() {
        return BadRequestException.localized("error.todo.query");
    }
}
