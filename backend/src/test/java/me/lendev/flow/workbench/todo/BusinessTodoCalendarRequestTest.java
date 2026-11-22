package me.lendev.flow.workbench.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import me.lendev.flow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class BusinessTodoCalendarRequestTest {

    @Test
    void visibleGridHasInclusiveDatesAndBoundedFilters() {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("fromDate", "2026-10-26");
        parameters.add("toDate", "2026-12-06");
        parameters.addAll("filter.taskKind", List.of("REVIEW", "CHECK", "REVIEW"));
        var calendar = BusinessTodoCalendarRequest.calendar(parameters);
        assertEquals(LocalDate.of(2026, 12, 6), calendar.window().to());
        assertEquals(List.of("REVIEW", "CHECK"), calendar.filters().get("taskKind"));
        parameters.set("toDate", "2026-12-07");
        assertThrows(BadRequestException.class, () -> BusinessTodoCalendarRequest.calendar(parameters));
    }
}
