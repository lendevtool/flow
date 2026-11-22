package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessContextTableDef.TODO_BUSINESS_CONTEXT;
import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessLinkTableDef.TODO_BUSINESS_LINK;
import static me.lendev.flow.workbench.todo.entity.table.TodoInstanceTableDef.TODO_INSTANCE;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryMethods;
import me.lendev.flow.support.ApiTestBase;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Option;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.OptionJoin;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Search;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarScene.Context;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarScene.ContextKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

@Import(BusinessTodoCalendarApiTest.TestScenes.class)
class BusinessTodoCalendarApiTest extends ApiTestBase {

    private static final String PREFIX = "/workbench/business-todo-calendars/";
    private static final String OVERVIEW = PREFIX + "fixture-overview";
    private static final String BY_RECORD = PREFIX + "fixture-record";
    private static final String BY_LINK = PREFIX + "fixture-link";
    private static final String PERMISSION = "FixtureCalendarView";
    private static final String MODULE = "fixture";
    private static final String RECORD_TYPE = "ORDER";
    private static final String LINK_TYPE = "SUPPLIER";
    private static final String RECORD_A = "Order-A";
    private static final String RECORD_B = "Order-B";
    private static final String RESTRICTED = "Order-Private";
    private static final String SUPPLIER_A = "Supplier-A";
    private static final String SUPPLIER_B = "Supplier-B";
    private static final String REVIEW = "REVIEW";
    private static final String VERIFY = "VERIFY";
    private static final String OLD = "OLD";
    private static final LocalDate FROM = LocalDate.of(2000, 1, 30);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final Instant CLOSED_AT = Instant.parse("2000-03-01T04:35:00Z");
    private static final long CROSSING = 9_007_199_254_740_993L;
    private static final long DUE_ONLY = 42L;
    private static final long DONE = 43L;
    private static final long CANCELLED = 44L;
    private static final long UNSCHEDULED_DONE = 45L;
    private static final long UNSCHEDULED_OPEN = 46L;
    private static final long HIDDEN = 47L;
    private static final long PLAN_OUTSIDE = 48L;
    private static final long UNLINKED = 49L;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void calendarCountsTheRangeBeforeStatusFiltering() throws Exception {
        fixture();
        String token = loginAsAdmin();
        var calendar = data(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO));
        assertEquals(List.of(id(DUE_ONLY), id(CROSSING), id(DONE)), ids(calendar));
        assertEquals(3, calendar.path("counts").path("all").asInt());
        assertEquals(2, calendar.path("counts").path("pending").asInt());
        assertEquals(1, calendar.path("counts").path("done").asInt());
        assertEquals(1, calendar.path("counts").path("cancelled").asInt());
        assertEquals(2, calendar.path("unscheduledCount").asInt());
        assertFalse(calendar.path("overflow").asBoolean());
        assertTrue(calendar.path("items").get(0).path("overdue").asBoolean());
        assertTrue(calendar.path("items").get(0).path("startDate").isNull());
        assertEquals(FROM.toString(), calendar.path("items").get(0).path("dueDate").asString());
        assertFalse(calendar.path("items").get(2).path("overdue").asBoolean());

        var done = data(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO, "status", "done"));
        assertEquals(List.of(id(DONE)), ids(done));
        assertEquals(calendar.path("counts"), done.path("counts"));
        assertEquals(1, done.path("unscheduledCount").asInt());
        var cancelled = data(getJson(OVERVIEW + "/calendar", token,
                "fromDate", FROM, "toDate", TO, "status", "cancelled"));
        assertEquals(List.of(id(CANCELLED)), ids(cancelled));
        assertEquals(0, cancelled.path("unscheduledCount").asInt());

        var workbench = data(getJson("/workbench/todos/calendar", token, "scope", "mine",
                "fromDate", FROM, "toDate", TO));
        assertTrue(workbench.valueStream().anyMatch(item -> item.path("id").asLong() == CROSSING));
    }

    @Test
    void completedTodoKeepsItsOriginalPlanAndHistoricalTaskKind() throws Exception {
        fixture();
        String token = loginAsAdmin();
        var historicKind = data(getJson(OVERVIEW + "/calendar", token,
                "fromDate", FROM, "toDate", TO, "filter.taskKind", OLD));
        assertEquals(List.of(id(DONE)), ids(historicKind));
        var completionDay = data(getJson(OVERVIEW + "/items", token,
                "mode", "day", "date", "2000-03-01", "status", "done"));
        assertEquals(0, completionDay.path("total").asInt());

        var detail = data(getJson(OVERVIEW + "/items/" + DONE, token));
        assertEquals("DONE", detail.path("status").asString());
        assertEquals(FROM.plusDays(1).toString(), detail.path("startDate").asString());
        assertEquals(CLOSED_AT, Instant.parse(detail.path("closedAt").asString()));
        assertEquals("历史环节", detail.path("title").asString());
        assertEquals("业务单据 " + RECORD_B, detail.path("recordLabel").asString());
        assertEquals("/fixtures/orders/" + RECORD_B, detail.path("businessUrl").asString());
        assertTrue(detail.path("workUrl").isNull());
        assertFalse(detail.has("description"));
        assertFalse(detail.has("allowedActions"));
    }

    @Test
    void selectorsComposeAndTheSameTodoAppearsOncePerBusinessView() throws Exception {
        fixture();
        String token = loginAsAdmin();
        var crossing = data(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO,
                "filter.supplier", SUPPLIER_A, "filter.supplier", SUPPLIER_B,
                "filter.taskKind", REVIEW, "filter.taskKind", VERIFY, "filter.assignee", "1"));
        assertEquals(List.of(id(CROSSING)), ids(crossing));
        assertEquals(1, crossing.path("counts").path("all").asInt());
        assertEquals("复核", crossing.path("items").get(0).path("title").asString());
        assertEquals(REVIEW, crossing.path("items").get(0).path("taskKindCode").asString());
        var supplierUnion = data(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO,
                "filter.supplier", SUPPLIER_A, "filter.supplier", SUPPLIER_B));
        assertEquals(List.of(id(DUE_ONLY), id(CROSSING)), ids(supplierUnion));

        for (String reference : List.of(SUPPLIER_A, SUPPLIER_B)) {
            var linked = data(getJson(BY_LINK + "/calendar", token, "contextId", reference,
                    "fromDate", FROM, "toDate", TO, "filter.taskKind", REVIEW));
            assertEquals(List.of(id(CROSSING)), ids(linked));
        }
        var recordCalendar = data(getJson(BY_RECORD + "/calendar", token, "contextId", RECORD_A,
                "fromDate", FROM, "toDate", TO, "filter.taskKind", REVIEW));
        assertEquals(List.of(id(CROSSING)), ids(recordCalendar));

        var day = data(getJson(OVERVIEW + "/items", token, "mode", "day", "date", FROM.plusDays(1),
                "page", 2, "size", 1));
        assertEquals(2, day.path("total").asInt());
        assertEquals(List.of(id(CROSSING)), ids(day));

        var unscheduled = data(getJson(OVERVIEW + "/items", token, "mode", "unscheduled", "page", 2, "size", 1));
        assertEquals(2, unscheduled.path("total").asInt());
        assertEquals(List.of(id(UNSCHEDULED_OPEN)), ids(unscheduled));
        var done = data(getJson(OVERVIEW + "/items", token, "mode", "unscheduled", "status", "done"));
        assertEquals(List.of(id(UNSCHEDULED_DONE)), ids(done));
        var invisibleMatch = data(getJson(OVERVIEW + "/items", token, "mode", "unscheduled", "status", "done",
                "filter.supplier", SUPPLIER_B));
        assertEquals(0, invisibleMatch.path("total").asInt());
    }

    @Test
    void rejectsUnauthorizedContextsAndHidesProtectedRecordsBeforeCountingOrResolvingOptions() throws Exception {
        fixture();
        String admin = loginAsAdmin();
        var created = data(postJson("/user", admin, map("username", "calendar_guard", "roleIds", List.of(2))));
        String temporary = created.path("temporaryPassword").asString();
        String password = "Calendar-Guard532!";
        String restricted = login("calendar_guard", temporary);
        data(postJson("/auth/password", restricted, map("oldPassword", temporary, "newPassword", password)));
        String viewer = login("calendar_guard", password);
        code(getJson(OVERVIEW + "/meta", viewer), 11003);

        body(getJson(PREFIX + "unregistered" + "/meta", admin), 404);
        body(getJson(BY_RECORD + "/meta", admin, "contextId", RESTRICTED), 404);
        body(getJson(BY_RECORD + "/meta", admin, "contextId", "order-a"), 404);
        body(getJson(BY_LINK + "/meta", admin, "contextId", "Supplier-Private"), 404);
        body(getJson(BY_LINK + "/items/" + HIDDEN, admin, "contextId", SUPPLIER_A), 404);
        body(getJson(BY_LINK + "/items/" + DUE_ONLY, admin, "contextId", SUPPLIER_A), 404);
        body(getJson(OVERVIEW + "/items/" + UNLINKED, admin), 404);

        var options = data(getJson(BY_LINK + "/options/assignee", admin, "contextId", SUPPLIER_A));
        assertEquals(2, options.path("total").asInt());
        assertEquals(List.of("1", "82"), options.path("items").valueStream()
                .map(item -> item.path("value").asString()).toList());
        var protectedOptions = data(getJson(BY_LINK + "/options/assignee", admin, "contextId", SUPPLIER_A,
                "keyword", "Private"));
        assertEquals(0, protectedOptions.path("total").asInt());
        var filtered = data(getJson(BY_LINK + "/calendar", admin, "contextId", SUPPLIER_A,
                "fromDate", FROM, "toDate", TO, "filter.assignee", 91));
        assertTrue(ids(filtered).isEmpty());
        assertEquals(0, filtered.path("counts").path("all").asInt());

        var quotedSearch = data(getJson(BY_LINK + "/options/assignee", admin, "contextId", SUPPLIER_A,
                "keyword", "O'Neil", "page", 1, "size", 1));
        assertEquals(1, quotedSearch.path("total").asInt());
        assertEquals("O'Neil", quotedSearch.path("items").get(0).path("label").asString());
        var selected = data(getJson(BY_LINK + "/options/assignee", admin, "contextId", SUPPLIER_A,
                "value", 82, "value", 91));
        assertEquals(1, selected.path("total").asInt());
        assertEquals("82", selected.path("items").get(0).path("value").asString());

        var link = data(getJson(BY_LINK + "/items/" + CROSSING, admin, "contextId", SUPPLIER_A)
                .andExpect(header().string("Cache-Control", allOf(containsString("private"), containsString("no-store")))));
        assertEquals("/fixtures/work/" + CROSSING, link.path("workUrl").asString());
        var unsafeLink = data(getJson(OVERVIEW + "/items/" + DUE_ONLY, admin));
        assertTrue(unsafeLink.path("businessUrl").isNull());
        assertTrue(unsafeLink.path("workUrl").isNull());
    }

    @Test
    void rejectsInvalidDimensionsDatesContextsAndPageSizes() throws Exception {
        String token = loginAsAdmin();
        var meta = data(getJson(BY_LINK + "/meta", token, "contextId", SUPPLIER_A));
        assertEquals("测试待办", meta.path("title").asString());
        assertEquals("month", meta.path("defaultView").asString());
        assertEquals(3, meta.path("dimensions").size());
        body(getJson(BY_LINK + "/meta", token), 400);
        body(getJson(OVERVIEW + "/meta", token, "contextId", RECORD_A), 400);
        body(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", FROM.plusDays(42)), 400);
        body(getJson(OVERVIEW + "/calendar", token, "fromDate", TO, "toDate", FROM), 400);
        body(getJson(OVERVIEW + "/calendar", token, "fromDate", "2000-02-31", "toDate", TO), 400);
        body(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO, "status", "complete"), 400);
        body(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO, "filter.unknown", REVIEW), 400);
        body(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", TO, "filter.assignee", "negative"), 400);
        body(getJson(OVERVIEW + "/items", token, "mode", "unscheduled", "size", 101), 400);

        Object[] query = Stream.concat(Stream.of("mode", "unscheduled"),
                IntStream.rangeClosed(1, 51).boxed().flatMap(value -> Stream.of("filter.assignee", value))).toArray();
        body(getJson(OVERVIEW + "/items", token, query), 400);
    }

    @Test
    void gridOverflowUsesTheSameAuthorizedRangeAsPagination() throws Exception {
        for (long id = 1000; id < 1500; id++) {
            insertTodo(id, "OPEN", null, null, FROM, RECORD_A, REVIEW, 1, "Admin");
        }
        String token = loginAsAdmin();
        var atLimit = data(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", FROM.plusDays(41)));
        assertFalse(atLimit.path("overflow").asBoolean());
        assertEquals(500, atLimit.path("items").size());
        insertTodo(1500, "DONE", null, null, FROM, RECORD_A, VERIFY, 1, "Admin");
        var overflow = data(getJson(OVERVIEW + "/calendar", token, "fromDate", FROM, "toDate", FROM));
        assertTrue(overflow.path("overflow").asBoolean());
        assertTrue(ids(overflow).isEmpty());
        assertEquals(501, overflow.path("counts").path("all").asInt());
        assertEquals(500, overflow.path("counts").path("pending").asInt());
        assertEquals(1, overflow.path("counts").path("done").asInt());

        var page = data(getJson(OVERVIEW + "/items", token, "mode", "range", "fromDate", FROM, "toDate", FROM,
                "page", 6, "size", 100));
        assertEquals(501, page.path("total").asInt());
        assertEquals(List.of("1500"), ids(page));
    }

    private void fixture() {
        insertTodo(CROSSING, "OPEN", FROM.minusDays(2), FROM.plusDays(1), null, RECORD_A, REVIEW, 1, "Admin");
        insertTodo(DUE_ONLY, "IN_PROGRESS", null, null, FROM, RECORD_B, VERIFY, 82, "O'Neil");
        insertTodo(DONE, "DONE", FROM.plusDays(1), TO, TO, RECORD_B, OLD, 82, "O'Neil");
        insertTodo(CANCELLED, "CANCELLED", null, null, FROM, RECORD_A, REVIEW, 1, "Admin");
        insertTodo(UNSCHEDULED_DONE, "DONE", null, null, null, RECORD_B, OLD, 82, "O'Neil");
        insertTodo(UNSCHEDULED_OPEN, "OPEN", null, null, null, RECORD_A, REVIEW, 1, "Admin");
        insertTodo(HIDDEN, "OPEN", null, null, FROM, RESTRICTED, REVIEW, 91, "Private Assignee");
        insertTodo(PLAN_OUTSIDE, "OPEN", FROM.minusDays(3), FROM.minusDays(1), FROM, RECORD_A, REVIEW, 1, "Admin");
        insertTodo(UNLINKED, "OPEN", null, null, FROM, null, REVIEW, 1, "Admin");
        link(CROSSING, SUPPLIER_A);
        link(CROSSING, SUPPLIER_B);
        link(DUE_ONLY, SUPPLIER_B);
        link(HIDDEN, SUPPLIER_A);
        link(UNSCHEDULED_DONE, SUPPLIER_A);
        link(UNSCHEDULED_DONE, SUPPLIER_B);
    }

    private void insertTodo(long id, String status, LocalDate start, LocalDate end, LocalDate due,
                            String recordId, String kind, int assignee, String assigneeName) {
        jdbc.update("""
                        INSERT INTO wb_todo
                        (id, title, description, origin, status, assigneeUserId, assigneeName,
                         scheduleMode, startDate, endDate, dueDate, closedAt)
                        VALUES (?, '原始待办标题', '内部说明', 'MANUAL', ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, status, assignee, assigneeName, start == null && due == null ? "NONE" : "FIXED",
                start, end, due, Set.of("DONE", "CANCELLED").contains(status)
                        ? LocalDateTime.ofInstant(CLOSED_AT, ZoneOffset.UTC) : null);
        if (recordId != null) {
            jdbc.update("""
                    INSERT INTO wb_todo_business_context (todoId, recordModule, recordType, recordId, taskKindCode, createdAt)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, id, MODULE, RECORD_TYPE, recordId, kind);
        }
    }

    private void link(long todoId, String ref) {
        jdbc.update("""
                INSERT INTO wb_todo_business_link (todoId, refModule, refType, refId, createdAt)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, todoId, MODULE, LINK_TYPE, ref);
    }

    private static List<String> ids(JsonNode response) {
        return response.path("items").valueStream().map(item -> item.path("todoId").asString()).toList();
    }

    private static String id(long todoId) {
        return Long.toString(todoId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestScenes {

        @Bean
        BusinessTodoCalendarScene overviewScene() {
            return new FixtureScene("fixture-overview", null);
        }

        @Bean
        BusinessTodoCalendarScene recordScene() {
            return new FixtureScene("fixture-record", new Context(ContextKind.RECORD, MODULE, RECORD_TYPE));
        }

        @Bean
        BusinessTodoCalendarScene linkScene() {
            return new FixtureScene("fixture-link", new Context(ContextKind.LINK, MODULE, LINK_TYPE));
        }
    }

    private record FixtureScene(Definition definition) implements BusinessTodoCalendarScene {

        private FixtureScene(String code, Context context) {
            this(new Definition(code, "测试待办", PERMISSION, MODULE, RECORD_TYPE, context, List.of(
                    BusinessTodoCalendarDimension.dictionary("taskKind", "任务环节", true, true,
                            List.of(new Option(REVIEW, "复核", true), new Option(VERIFY, "验证", true),
                                    new Option(OLD, "历史环节", false)),
                            Set.of(REVIEW, VERIFY, OLD)::contains, TODO_BUSINESS_CONTEXT.TASK_KIND_CODE::in),
                    BusinessTodoCalendarDimension.searchable("supplier", "供应商", false, true,
                            value -> value.matches("Supplier-[A-Z]+"), supplierSearch()),
                    BusinessTodoCalendarDimension.searchable("assignee", "负责人", false, true,
                            value -> value.matches("[1-9][0-9]{0,8}"),
                            new Search(TODO_INSTANCE.ASSIGNEE_USER_ID, TODO_INSTANCE.ASSIGNEE_NAME, null, null)))));
        }

        private static Search supplierSearch() {
            var link = TODO_BUSINESS_LINK.as("fixture_supplier_option");
            var rowPermission = QueryMethods.bracket(link.REF_ID.ne(SUPPLIER_B)
                    .or(TODO_INSTANCE.ID.ne(UNSCHEDULED_DONE)));
            return new Search(link.REF_ID, link.REF_ID, null, viewer -> List.of(new OptionJoin(link,
                    link.TODO_ID.eq(TODO_INSTANCE.ID).and(link.REF_MODULE.eq(MODULE)).and(link.REF_TYPE.eq(LINK_TYPE))
                            .and(rowPermission.clone()))));
        }

        @Override
        public QueryCondition visibleTodos(int viewer) {
            return TODO_BUSINESS_CONTEXT.RECORD_ID.in(RECORD_A, RECORD_B);
        }

        @Override
        public boolean canReadContext(BusinessTodoRef context, int viewer) {
            return switch (context.objectType()) {
                case RECORD_TYPE -> Set.of(RECORD_A, RECORD_B).contains(context.objectId());
                case LINK_TYPE -> Set.of(SUPPLIER_A, SUPPLIER_B).contains(context.objectId());
                default -> false;
            };
        }

        @Override
        public String taskLabel(String kind) {
            return Map.of(REVIEW, "复核", VERIFY, "验证", OLD, "历史环节").get(kind);
        }

        @Override
        public Map<BusinessTodoRef, String> recordLabels(Set<BusinessTodoRef> records, int viewer) {
            return records.stream().collect(Collectors.toMap(reference -> reference,
                    reference -> "业务单据 " + reference.objectId()));
        }

        @Override
        public Links links(BusinessTodoRef businessRecord, long todoId, int viewer) {
            if (todoId == DUE_ONLY) {
                return new Links("//foreign.example/record", "https://foreign.example/work");
            }
            return new Links("/fixtures/orders/" + businessRecord.objectId(),
                    todoId == CROSSING ? "/fixtures/work/" + todoId : null);
        }
    }
}
