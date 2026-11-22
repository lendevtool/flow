package me.lendev.flow.audit.query;

import static me.lendev.flow.audit.query.mapper.AuditQueryMapper.CHANGE;
import static me.lendev.flow.audit.query.mapper.AuditQueryMapper.LOG;
import static me.lendev.flow.audit.query.mapper.AuditQueryMapper.QUEUE;

import java.time.*;
import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.query.mapper.AuditQueryMapper;
import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.request.PageRequest;
import me.lendev.flow.common.response.Page;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计日志、变更明细和队列状态的只读查询服务，使用固定 SQL 和绑定参数，日志时间范围限定在 90 天内。
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final String ACTOR_KEYWORD = "actorKeyword";
    private static final Set<String> JSON_COLUMNS = Set.of("beforeData", "afterData", "changes");

    private final AuditQueryMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * 分页查询审计日志摘要。
     *
     * @param request 筛选条件与分页参数，时间范围限定在 90 天内
     * @return 日志摘要分页结果
     * @throws BadRequestException 时间范围非法时抛出
     */
    public Page<Map<String, Object>> logs(AuditQueryRequest request) {
        Filter filter = filter(request);
        long total = mapper.countLogs(filter.args());
        var rows = mapper.logs(filter.args(), offset(request), size(request));
        return page(formatRows(rows, LOG), total);
    }

    /**
     * 读取单条审计日志及其对象变更明细分页。
     *
     * @param id      审计日志主键
     * @param request 明细分页参数
     * @return 日志摘要与明细分页
     * @throws BadRequestException 日志不存在时抛出
     */
    public Map<String, Object> detail(long id, PageRequest request) {
        var log = mapper.findLog(id);
        if (log == null) {
            throw BadRequestException.localized("error.audit.log.missing");
        }
        long count = mapper.countChanges(id);
        var changes = mapper.changes(id, offset(request), size(request));
        return Map.of("log", formatRow(log, LOG.split(",")), "objects", page(formatRows(changes, CHANGE), count));
    }

    /**
     * 分页查询 outbox 队列记录。
     *
     * @param request 分页参数
     * @param status  状态筛选；为空时仅返回 RETRY/DEAD
     * @return 队列记录分页结果
     * @throws BadRequestException 状态值非法时抛出
     */
    public Page<Map<String, Object>> outbox(PageRequest request, String status) {
        String filter = text(status) ? status : null;
        if (filter != null && !Set.of("PREPARED", "PENDING", "PROCESSING", "RETRY", "DONE", "DEAD").contains(filter)) {
            throw BadRequestException.localized("error.audit.queue.status.invalid");
        }
        long total = mapper.countQueue(filter);
        var rows = mapper.pageQueue(filter, offset(request), size(request));
        return page(formatRows(rows, QUEUE), total);
    }

    /**
     * 汇总队列各状态积压数量与最早未完成任务时间。
     *
     * @return 含 counts 与 oldestPendingAt 的状态概览
     */
    public Map<String, Object> status() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : List.of("PREPARED", "PENDING", "PROCESSING", "RETRY", "DEAD")) {
            counts.put(state, 0L);
        }
        mapper.queueCounts().forEach(row -> counts.put(row.getStatus(), row.getAmount()));
        LocalDateTime oldest = mapper.oldestPendingAt();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("oldestPendingAt", oldest == null ? null : oldest.toInstant(ZoneOffset.UTC));
        return result;
    }

    /**
     * 提供查询界面所需的操作与模块下拉选项。
     *
     * @return 含 operations 与 modules 的选项集合
     */
    public Map<String, Object> options() {
        List<Map<String, String>> operations = Arrays.stream(AuditOperation.values())
                .map(operation -> Map.of("value", operation.code(), "label", operation.label(), "module", operation.module())).toList();
        return Map.of("operations", operations, "modules", List.of(
                option("PARAM", "参数管理"), option("FIELD", "字段管理"), option("TRADING_CALENDAR", "交易日历"),
                option("TODO", "工作台待办"), option("DOCUMENT_CENTER", "文档中心"), option("DOCUMENT_GENERATION", "文档生成"), option("MAIL", "邮件管理"),
                option("INSTITUTION", "机构管理"),
                option("AUTH", "登录认证"),
                option("AUDIT", "审计管理"), option("DEPARTMENT", "组织架构"), option("NOTIFICATION_GROUP", "通知群组"),
                option("USER", "用户管理"),
                option("ROLE", "角色管理"), option("PERMISSION", "资源管理"), option("POLICY", "系统策略")));
    }

    private static Map<String, String> option(String value, String label) {
        return Map.of("value", value, "label", label);
    }

    /**
     * 导出数据（含导出条件快照）。
     */
    public record ExportData(List<Map<String, Object>> rows, Map<String, Object> criteria) {

    }

    /**
     * 按筛选条件读取导出行（上限 5000 条）并附带导出条件快照。
     *
     * @param request 导出筛选条件
     * @return 导出行与条件快照
     * @throws BadRequestException 时间范围非法或超出导出上限时抛出
     */
    public ExportData export(AuditQueryRequest request) {
        Filter filter = filter(request);
        // 多取一条（5001）用于判断是否超出 5000 条导出上限。
        List<Map<String, Object>> rows = formatRows(mapper.logs(filter.args(), 0, 5001), LOG);
        if (rows.size() > 5000) {
            throw BadRequestException.localized("error.audit.export.limit");
        }
        return new ExportData(rows, filter.criteria());
    }

    private record Filter(Map<String, Object> args, Map<String, Object> criteria) {

    }

    private Filter filter(AuditQueryRequest request) {
        Instant end = request.getEndTime() == null ? clock.instant() : request.getEndTime();
        Instant start = request.getStartTime() == null ? end.minus(Duration.ofDays(7)) : request.getStartTime();
        if (start.isAfter(end) || Duration.between(start, end).compareTo(Duration.ofDays(90)) > 0) {
            throw BadRequestException.localized("error.audit.range.invalid");
        }
        Map<String, Object> args = new HashMap<>();
        args.put("startTime", AuditStore.time(start));
        args.put("endTime", AuditStore.time(end));
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("startTime", start.toString());
        criteria.put("endTime", end.toString());
        exact(args, criteria, "actorId", request.getActorId());
        exact(args, criteria, "moduleCode", request.getModuleCode());
        exact(args, criteria, "operationCode", request.getOperationCode());
        exact(args, criteria, "result", request.getResult());
        exact(args, criteria, "requestId", request.getRequestId());
        exact(args, criteria, "operationId", request.getOperationId());
        String keyword = request.getActorKeyword();
        if (text(keyword)) {
            // 以 ! 作为 ESCAPE 字符，转义关键字中的 !、%、_，防止 LIKE 通配符注入。
            args.put(ACTOR_KEYWORD, "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            criteria.put(ACTOR_KEYWORD, keyword);
        } else {
            args.put(ACTOR_KEYWORD, null);
        }
        Map<String, Object> objectCriteria = new LinkedHashMap<>();
        exact(args, objectCriteria, "objectType", request.getObjectType());
        exact(args, objectCriteria, "objectId", request.getObjectId());
        exact(args, objectCriteria, "objectCode", request.getObjectCode());
        exact(args, objectCriteria, "parentType", request.getParentType());
        exact(args, objectCriteria, "parentId", request.getParentId());
        args.put("filterObjects", objectCriteria.isEmpty() ? 0 : 1);
        criteria.putAll(objectCriteria);
        return new Filter(args, criteria);
    }

    private static void exact(Map<String, Object> args, Map<String, Object> criteria, String key, String value) {
        if (text(value)) {
            args.put(key, value);
            criteria.put(key, value);
        } else {
            args.put(key, null);
        }
    }

    private List<Map<String, Object>> formatRows(List<?> rows, String columns) {
        String[] names = columns.split(",");
        return rows.stream().map(row -> formatRow(row, names)).toList();
    }

    private Map<String, Object> formatRow(Object row, String[] names) {
        var properties = SystemMetaObject.forObject(row);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) {
            Object value = properties.getValue(name);
            if (value instanceof LocalDateTime date) {
                value = date.toInstant(ZoneOffset.UTC);
            } else if (value instanceof String raw && JSON_COLUMNS.contains(name)) {
                value = json.readTree(raw);
            } else if (value != null && (name.equals("id") || name.equals("logId"))) {
                value = value.toString();
            }
            result.put(name, value);
        }
        return result;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static int size(PageRequest request) {
        return request.getPageSize() == null ? 10 : Math.clamp(request.getPageSize(), 1, 500);
    }

    private static long offset(PageRequest request) {
        long page = request.getPageNo() == null ? 1L : Math.max(1L, request.getPageNo().longValue());
        return (page - 1L) * size(request);
    }

    private static <T> Page<T> page(List<T> rows, long total) {
        Page<T> page = new Page<>();
        page.setPageData(rows);
        page.setTotal(total);
        return page;
    }
}
