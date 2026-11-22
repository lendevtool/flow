package me.lendev.flow.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import me.lendev.flow.support.ApiTestBase;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立 H2 和测试 Redis 上压测已登录的 MockMvc 入口。通过 -Dflow.load.enabled=true 启用。 参数 flow.load.requests、flow.load.concurrency、flow.load.rows 分别控制读请求数、并发数和数据量。
 */
@EnabledIfSystemProperty(named = "flow.load.enabled", matches = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:java_optimization_load;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "document.cleanup.enabled=false"
})
@Import(JavaOptimizationLoadTest.MetricsConfiguration.class)
class JavaOptimizationLoadTest extends ApiTestBase {

    private static final LocalDate BASE = LocalDate.of(2099, 6, 1);
    private static final String SCHEDULE = "/workbench/todos/schedule-preview";
    private static final String PARAM_CODE = "load_opt_sort";
    private static final String DOCUMENT_NAME = "Java optimization load fixture";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SqlMetrics sql;

    @Test
    void exercisesConcurrentReadsAndLargeReorders() throws Exception {
        int requests = setting("requests", 120, 10_000);
        int concurrency = Math.min(setting("concurrency", 8, 64), requests);
        int rows = setting("rows", 5000, 5000);
        int sortRequests = setting("sortRequests", 6, 100);
        String token = loginAsAdmin();
        long documentId = seedDocument(rows);
        long groupId = seedParameters(rows);
        seedCalendar();

        var scheduleRequest = map("scheduleMode", "TRADING", "marketCode", "CN", "tradeBaseDate", BASE,
                "startTradeOffset", 1, "endTradeOffset", 2, "dueTradeOffset", 3);
        Operation preview = index -> {
            var result = data(postJson(SCHEDULE, token, scheduleRequest));
            assertEquals(BASE.plusDays(3).toString(), result.path("dueDate").asString());
        };
        preview.run(0);
        sql.calendarReads.reset();
        measure("schedule-preview", requests, concurrency, preview);
        assertEquals(2L * requests, sql.calendarReads.sum(), "每次排期只查基准日和一个窗口");
        System.out.printf("  calendar SQL reads/request=%.1f%n", (double) sql.calendarReads.sum() / requests);

        Operation detail = index -> {
            var result = data(getJson("/documents/" + documentId, token));
            assertEquals(rows, result.path("versions").size());
        };
        detail.run(0);
        sql.documentLocks.reset();
        measure("document-detail", requests, concurrency, detail);
        assertEquals(0, sql.documentLocks.sum(), "文档详情不使用行锁");

        var ids = jdbc.queryForList("SELECT id FROM sys_param_item WHERE groupId = ? ORDER BY sort", Long.class, groupId);
        var reversed = ids.reversed();
        String sortUrl = "/param/groups/" + groupId + "/sort";
        var sameOrder = map("ids", ids);
        var reverseOrder = map("ids", reversed);
        data(patchJson(sortUrl, token, sameOrder));
        sql.sortUpdates.reset();
        measure("param-sort", sortRequests, 1,
                index -> data(patchJson(sortUrl, token, index % 2 == 0 ? reverseOrder : sameOrder)));
        long updates = sql.sortUpdates.sum();
        assertTrue(updates <= (long) sortRequests * Math.ceilDiv(rows, 250));
        assertTrue(rows == 1 || updates > 0);
        var expected = sortRequests % 2 == 0 ? ids : reversed;
        assertEquals(expected, jdbc.queryForList(
                "SELECT id FROM sys_param_item WHERE groupId = ? ORDER BY sort", Long.class, groupId));
        long version = jdbc.queryForObject("SELECT version FROM sys_param_group WHERE id = ?", Long.class, groupId);
        sql.sortUpdates.reset();
        data(patchJson(sortUrl, token, map("ids", expected)));
        assertEquals(0, sql.sortUpdates.sum(), "原顺序提交不更新明细");
        assertEquals(version, jdbc.queryForObject("SELECT version FROM sys_param_group WHERE id = ?", Long.class, groupId));
        System.out.printf("  sort SQL updates/request=%.1f; unchanged sort SQL updates=0%n",
                (double) updates / sortRequests);
    }

    private long seedDocument(int rows) {
        jdbc.update("INSERT INTO doc_document (name, createdBy, updatedBy) VALUES (?, 1, 1)", DOCUMENT_NAME);
        long id = jdbc.queryForObject("SELECT id FROM doc_document WHERE name = ?", Long.class, DOCUMENT_NAME);
        jdbc.batchUpdate("""
                INSERT INTO doc_document_version
                    (documentId, versionDate, dailySequence, originalFileName, contentType, sizeBytes, storageKey, status, createdBy)
                VALUES (?, ?, ?, 'fixture.txt', 'text/plain', 1000, ?, 'READY', 1)
                """, IntStream.rangeClosed(1, rows)
                .mapToObj(i -> new Object[]{id, BASE, i, "load-test-version-" + i}).toList());
        return id;
    }

    private long seedParameters(int rows) {
        jdbc.update("INSERT INTO sys_param_group (code, name) VALUES (?, 'Load test sort group')", PARAM_CODE);
        long id = jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = ?", Long.class, PARAM_CODE);
        jdbc.batchUpdate("INSERT INTO sys_param_item (groupId, code, name, sort) VALUES (?, ?, ?, ?)",
                IntStream.rangeClosed(1, rows).mapToObj(i -> new Object[]{id, "item_" + i, "Item " + i, i}).toList());
        return id;
    }

    private void seedCalendar() {
        long marketId = jdbc.queryForObject("""
                SELECT i.id FROM sys_param_item i JOIN sys_param_group g ON g.id = i.groupId
                WHERE g.code = 'TRADING_MARKET' AND i.code = 'CN'
                """, Long.class);
        jdbc.batchUpdate("""
                INSERT INTO sys_trading_calendar_day (marketItemId, calendarDate, calendarDate8, isTradingDay, source)
                VALUES (?, ?, ?, 1, 'INIT')
                """, IntStream.rangeClosed(0, 128).mapToObj(i -> {
            var date = BASE.plusDays(i);
            return new Object[]{marketId, date, DateTimeFormatter.BASIC_ISO_DATE.format(date)};
        }).toList());
    }

    private static int setting(String name, int fallback, int maximum) {
        int value = Integer.parseInt(System.getProperty("flow.load." + name, Integer.toString(fallback)));
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("flow.load." + name + " 需介于 1 和 " + maximum + " 之间");
        }
        return value;
    }

    private static void measure(String name, int count, int concurrency, Operation operation) throws Exception {
        long[] durations = new long[count];
        var ready = new CountDownLatch(concurrency);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            var workers = IntStream.range(0, concurrency).<Future<Void>>mapToObj(worker -> executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int index = worker; index < count; index += concurrency) {
                    long before = System.nanoTime();
                    operation.run(index);
                    durations[index] = System.nanoTime() - before;
                }
                return null;
            })).toList();
            long elapsed;
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS), "等待压测线程启动超时");
                long before = System.nanoTime();
                start.countDown();
                long deadline = before + TimeUnit.MINUTES.toNanos(3);
                for (var worker : workers) {
                    worker.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
                elapsed = System.nanoTime() - before;
            } finally {
                workers.forEach(worker -> worker.cancel(true));
            }
            Arrays.sort(durations);
            System.out.printf(Locale.ROOT, "%s requests=%d concurrency=%d throughput=%.1f req/s "
                            + "p50=%.2f ms p95=%.2f ms p99=%.2f ms%n",
                    name, count, concurrency, count * 1_000_000_000.0 / elapsed,
                    percentile(durations, 0.50), percentile(durations, 0.95), percentile(durations, 0.99));
        }
    }

    private static double percentile(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1] / 1_000_000.0;
    }

    @FunctionalInterface
    private interface Operation {

        void run(int index) throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfiguration {

        @Bean
        SqlMetrics sqlMetrics() {
            return new SqlMetrics();
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {java.sql.Connection.class, Integer.class}))
    static class SqlMetrics implements Interceptor {

        private final LongAdder calendarReads = new LongAdder();
        private final LongAdder documentLocks = new LongAdder();
        private final LongAdder sortUpdates = new LongAdder();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((StatementHandler) invocation.getTarget()).getBoundSql()
                    .getSql().stripLeading().toLowerCase(Locale.ROOT);
            if (statement.startsWith("select") && statement.contains("sys_trading_calendar_day")) {
                calendarReads.increment();
            }
            if (statement.startsWith("select") && statement.contains("doc_document") && statement.contains("for update")) {
                documentLocks.increment();
            }
            if (statement.startsWith("update sys_param_item")) {
                sortUpdates.increment();
            }
            return invocation.proceed();
        }
    }
}
